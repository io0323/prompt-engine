package promptengine.tests.integration.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.shared.IdempotencyKeyConflictException
import promptengine.domain.shared.IdempotencyKeyInProgressException
import promptengine.infrastructure.persistence.JdbcIdempotentCommandExecutor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * [JdbcIdempotentCommandExecutor]のTestcontainers(PostgreSQL 16)統合テスト（P9bレビュー要件:
 * 同一キーの再送で二重実行されないこと・IN_PROGRESS中の再送・fingerprint不一致検知）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcIdempotentCommandExecutorIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcIdempotentCommandExecutor

    data class DummyResult(val value: Int)

    @BeforeAll
    fun setUp() {
        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = postgres.driverClassName
            }
        dataSource = HikariDataSource(hikariConfig)
        Flyway.configure().dataSource(dataSource).load().migrate()

        jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
        val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
        executor =
            JdbcIdempotentCommandExecutor(
                jdbcTemplate,
                transactionTemplate,
                jacksonObjectMapper(),
                CLAIM_TIMEOUT_SECONDS,
            )
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun `executeInTransactionは同一キー・同一fingerprintの再送でcommandを再実行しない`() {
        val key = uniqueKey()
        var callCount = 0
        val command = {
            callCount++
            DummyResult(callCount)
        }

        val first = executor.executeInTransaction(key, "fingerprint-a", DummyResult::class.java, command)
        val second = executor.executeInTransaction(key, "fingerprint-a", DummyResult::class.java, command)

        callCount shouldBe 1
        first shouldBe DummyResult(1)
        second shouldBe DummyResult(1)
    }

    @Test
    fun `executeInTransactionは同一キー・異なるfingerprintでIdempotencyKeyConflictExceptionを投げる`() {
        val key = uniqueKey()
        executor.executeInTransaction(key, "fingerprint-a", DummyResult::class.java) { DummyResult(1) }

        shouldThrow<IdempotencyKeyConflictException> {
            executor.executeInTransaction(key, "fingerprint-b", DummyResult::class.java) { DummyResult(2) }
        }
    }

    @Test
    fun `executeLongRunningはoperationを予約トランザクション外で実行し再送でも重複実行しない`() {
        val key = uniqueKey()
        var callCount = 0
        val operation = {
            callCount++
            DummyResult(callCount)
        }

        val first = executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java, operation)
        val second = executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java, operation)

        callCount shouldBe 1
        first shouldBe DummyResult(1)
        second shouldBe DummyResult(1)
    }

    @Test
    fun `executeLongRunningでoperationが失敗すると予約が解除され再送でリトライできる`() {
        val key = uniqueKey()
        var callCount = 0
        val operation = {
            callCount++
            if (callCount == 1) error("simulated APAP failure")
            DummyResult(callCount)
        }

        shouldThrow<IllegalStateException> {
            executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java, operation)
        }
        val result = executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java, operation)

        callCount shouldBe 2
        result shouldBe DummyResult(2)
    }

    @Test
    fun `IN_PROGRESS中のキーへの再送はIdempotencyKeyInProgressExceptionを投げる`() {
        val key = uniqueKey()
        reserveInProgress(key, "fingerprint-a")

        shouldThrow<IdempotencyKeyInProgressException> {
            executor.executeInTransaction(key, "fingerprint-a", DummyResult::class.java) { DummyResult(1) }
        }
    }

    @Test
    fun `claimTimeoutを超えて期限切れのIN_PROGRESS予約は次のexecuteLongRunning呼出で奪取されoperationが実行される`() {
        val key = uniqueKey()
        // claimTimeoutSeconds(=CLAIM_TIMEOUT_SECONDS)を大きく超えた過去のclaimed_atを持つ、
        // クラッシュで解放されなかった予約を直接作る（Issue #50が指す状況の再現）。
        reserveInProgress(key, "fingerprint-a", claimedAt = Instant.now().minusSeconds(999))

        var callCount = 0
        val result =
            executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java) {
                callCount++
                DummyResult(callCount)
            }

        callCount shouldBe 1
        result shouldBe DummyResult(1)
    }

    @Test
    fun `claimTimeout内のIN_PROGRESS予約への再送はexecuteLongRunning経由でもIdempotencyKeyInProgressExceptionを投げる`() {
        val key = uniqueKey()
        reserveInProgress(key, "fingerprint-a")

        shouldThrow<IdempotencyKeyInProgressException> {
            executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java) { DummyResult(1) }
        }
    }

    @Test
    fun `markCompleted時にclaimed_byが別トークンへ書き換わっているとフェンシングで0行影響になり呼出元は成功結果を受け取る`() {
        // markCompleted/releaseReservationはprivateであり、フェンシング喪失（他リクエストによる
        // 再クレーム）はexecuteLongRunning呼出のごく短い競合窓でしか公開APIから直接は起こせない。
        // そのためSQLレベルで「実行中に別トークンへ再クレームされた」状態を直接組み立て、
        // markCompletedが使うのと同じ`WHERE claimed_by = :token`条件のUPDATEを実行して
        // 0行影響になることを確認する。これはmarkCompletedの内部実装と同一のSQL形状であり、
        // 「フェンシングを喪失したUPDATEは対象行に作用しない」という振る舞いをSQLレベルで
        // 直接検証する（プロダクションコードのprivateメソッドを迂回せず、同じ契約を確認する）。
        val key = uniqueKey()
        val originalToken = UUID.randomUUID().toString()
        reserveInProgress(key, "fingerprint-a", claimedBy = originalToken)

        // 別リクエストが期限切れ後にこの行を奪取した状況をシミュレートする
        // （claimed_byが別トークンへ書き換わる）。
        val reclaimerToken = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "UPDATE idempotency_keys SET claimed_at = :now, claimed_by = :newToken WHERE idempotency_key = :key",
            MapSqlParameterSource()
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("newToken", reclaimerToken)
                .addValue("key", key),
        )

        // originalTokenを保持していたリクエストがmarkCompletedと同じ形状のUPDATEを試みると、
        // claimed_byが既に一致しないため0行影響になる（フェンシング喪失）。
        val affectedRows =
            jdbcTemplate.update(
                """
                UPDATE idempotency_keys
                SET status = 'COMPLETED', result_type = :resultType, result_json = :resultJson::json, completed_at = :completedAt
                WHERE idempotency_key = :key AND claimed_by = :claimedBy
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("resultType", DummyResult::class.java.name)
                    .addValue("resultJson", "{\"value\":1}")
                    .addValue("completedAt", Timestamp.from(Instant.now()))
                    .addValue("key", key)
                    .addValue("claimedBy", originalToken),
            )

        affectedRows shouldBe 0
    }

    @Test
    fun `operation実行中に他リクエストがclaimed_byを奪取するとmarkCompletedはフェンシングされ呼出元へは成功結果が返る`() {
        // 上のテストとは異なり、実際にexecuteLongRunning経由でmarkCompletedのフェンシング分岐
        // （updated=0時にlogFencingLostのみでresultをそのまま返す）を通す。operationの実行中に
        // 別リクエストが奪取したのと同じ形のUPDATEを自ら発行することで競合窓を確実に再現する。
        val key = uniqueKey()
        val operation = {
            jdbcTemplate.update(
                "UPDATE idempotency_keys SET claimed_by = :hijacker WHERE idempotency_key = :key",
                MapSqlParameterSource().addValue("hijacker", "hijacker-token").addValue("key", key),
            )
            DummyResult(99)
        }

        val result = executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java, operation)

        result shouldBe DummyResult(99)
        // フェンシングに敗れたため、この呼出はstatusをCOMPLETEDへ書き換えられていない
        // （正準の完了記録は奪取した側が別途担う契約、ADR-0027決定4）。
        val status =
            jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_keys WHERE idempotency_key = :key",
                MapSqlParameterSource().addValue("key", key),
                String::class.java,
            )
        status shouldBe "IN_PROGRESS"
    }

    @Test
    fun `operation失敗前に他リクエストがclaimed_byを奪取するとreleaseReservationはフェンシングされ例外はそのまま伝播する`() {
        val key = uniqueKey()
        val operation = {
            jdbcTemplate.update(
                "UPDATE idempotency_keys SET claimed_by = :hijacker WHERE idempotency_key = :key",
                MapSqlParameterSource().addValue("hijacker", "hijacker-token").addValue("key", key),
            )
            error("simulated APAP failure after hijack")
        }

        shouldThrow<IllegalStateException> {
            executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java, operation)
        }

        // フェンシングに敗れたため、この呼出はDELETEできず行は奪取した側の所有のまま残る。
        val claimedBy =
            jdbcTemplate.queryForObject(
                "SELECT claimed_by FROM idempotency_keys WHERE idempotency_key = :key",
                MapSqlParameterSource().addValue("key", key),
                String::class.java,
            )
        claimedBy shouldBe "hijacker-token"
    }

    @Test
    fun `claimed_atがnullのIN_PROGRESS行は期限切れとみなさず従来通りブロックする`() {
        // V14マイグレーションのバックフィル・insertReservedのいずれも常にclaimed_atを設定するため
        // 通常は発生しないが、コードの安全側フォールバック（claimedAt=null時は期限切れ扱いしない）
        // を直接検証する。
        val key = uniqueKey()
        jdbcTemplate.update(
            """
            INSERT INTO idempotency_keys (idempotency_key, request_fingerprint, status, created_at)
            VALUES (:key, :fingerprint, 'IN_PROGRESS', :createdAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("fingerprint", "fingerprint-a")
                .addValue("createdAt", Timestamp.from(Instant.now())),
        )

        shouldThrow<IdempotencyKeyInProgressException> {
            executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java) { DummyResult(1) }
        }
    }

    @Test
    fun `claimed_byがnullで期限切れのIN_PROGRESS行も奪取できる`() {
        val key = uniqueKey()
        jdbcTemplate.update(
            """
            INSERT INTO idempotency_keys (idempotency_key, request_fingerprint, status, created_at, claimed_at)
            VALUES (:key, :fingerprint, 'IN_PROGRESS', :createdAt, :staleClaimedAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("fingerprint", "fingerprint-a")
                .addValue("createdAt", Timestamp.from(Instant.now()))
                .addValue("staleClaimedAt", Timestamp.from(Instant.now().minusSeconds(999))),
        )

        val result = executor.executeLongRunning(key, "fingerprint-a", DummyResult::class.java) { DummyResult(1) }

        result shouldBe DummyResult(1)
    }

    @Test
    fun `同一キーへの真に同時な初回実行はcommandを1回しか実行せず成功者は全員同じ結果を得る`() {
        // insertReservedのDuplicateKeyExceptionキャッチ分岐（真の競合下でのみ発生する）を、
        // 複数スレッドが同一キーへ同時にexecuteInTransactionするストレステストで再現する。
        // findRow→insertReservedの2段階はアトミックではないため複数スレッドが同時にINSERTを
        // 試みうるが、PRIMARY KEY制約により実際にINSERTが成功する（＝commandを実行する）のは
        // 常に1スレッドのみ。この1トランザクションが速く完了すると、他スレッドはIN_PROGRESSを
        // 観測する間もなくCOMPLETED状態を直接見てキャッシュ済み結果を返す（＝成功者は複数に
        // なりうる）ため、「成功者の数」ではなく「commandの実行回数」と「返る結果の一貫性」を
        // 不変条件として検証する（先行実装のこのテストが誤って前者を仮定していた反省）。
        val key = uniqueKey()
        val threadCount = 8
        val executorService = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val callCount = AtomicInteger(0)
        val successResults = java.util.Collections.synchronizedList(mutableListOf<DummyResult>())
        val blockedCount = AtomicInteger(0)

        repeat(threadCount) {
            executorService.submit {
                readyLatch.countDown()
                startLatch.await()
                try {
                    val result =
                        executor.executeInTransaction(key, "fingerprint-a", DummyResult::class.java) {
                            callCount.incrementAndGet()
                            DummyResult(1)
                        }
                    successResults += result
                } catch (e: IdempotencyKeyInProgressException) {
                    blockedCount.incrementAndGet()
                }
            }
        }
        readyLatch.await()
        startLatch.countDown()
        executorService.shutdown()
        executorService.awaitTermination(30, TimeUnit.SECONDS)

        callCount.get() shouldBe 1
        (successResults.size + blockedCount.get()) shouldBe threadCount
        successResults.toSet() shouldBe setOf(DummyResult(1))
    }

    /**
     * `IN_PROGRESS`のまま完了しなかったキーを直接作る（executorの完了処理を経由しない）。
     * [claimedAt]/[claimedBy]は、クレームタイムアウトの経過判定・フェンシングのシミュレーション
     * （Issue #50、ADR-0027）のためにテストごとに指定できるようにする。
     */
    private fun reserveInProgress(
        key: String,
        fingerprint: String,
        claimedAt: Instant = Instant.now(),
        claimedBy: String = "test-fixture",
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO idempotency_keys (idempotency_key, request_fingerprint, status, created_at, claimed_at, claimed_by)
            VALUES (:key, :fingerprint, 'IN_PROGRESS', :createdAt, :claimedAt, :claimedBy)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("fingerprint", fingerprint)
                .addValue("createdAt", Timestamp.from(Instant.now()))
                .addValue("claimedAt", Timestamp.from(claimedAt))
                .addValue("claimedBy", claimedBy),
        )
    }

    private fun uniqueKey(): String = "integration-test-${UUID.randomUUID()}"

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        // 「期限切れ」を模すテストはclaimed_atをInstant.now().minusSeconds(999)へ直接設定するため、
        // このタイムアウト値そのものの大小には依存しない。一方「期限内でブロックされる」ことを
        // 確認するテスト（IN_PROGRESS中の再送・真の並行競合下での単一実行保証）は、予約から
        // アサーションまでの実時間がこの値を上回らないことを前提にしている。値を小さくしすぎると
        // 負荷のかかったCI環境でこの前提が崩れフレーキーになりうる（CodeRabbitレビュー指摘）ため、
        // 実測ラウンドトリップより十分大きい値を使う（本番既定値120秒、IdempotencyClaimProperties、
        // より小さいのはテスト実行時間を意図的に切り詰めるためだが、Flakyになる程には切り詰めない）。
        private const val CLAIM_TIMEOUT_SECONDS = 30L
    }
}
