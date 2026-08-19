package promptengine.tests.integration.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkItemKey
import promptengine.domain.benchmark.BenchmarkItemScores
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcBenchmarkItemResultRepository
import promptengine.infrastructure.persistence.JdbcBenchmarkRepository
import promptengine.infrastructure.persistence.JdbcGoldenDatasetRepository
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * [JdbcBenchmarkItemResultRepository]のTestcontainers(PostgreSQL 16)統合テスト
 * （設計書§12 `benchmark_item_results`、ADR-0035決定3、フェーズ(c)）。
 *
 * このテストが検証する中心的な要件（`docs/prompts/m2-4b-c.md`「1が今回の要点」）は
 * フェンシングが実際に効くこと（ワーカーA claim → claim_timeout経過 → ワーカーB 再claim →
 * Aの確定が失敗すること）。`OutboxRelayIntegrationTest`の同種テストと同じ構造で再現する。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcBenchmarkItemResultRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var datasetRepository: JdbcGoldenDatasetRepository
    private lateinit var benchmarkRepository: JdbcBenchmarkRepository
    private lateinit var itemResultRepository: JdbcBenchmarkItemResultRepository

    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

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
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
        datasetRepository = JdbcGoldenDatasetRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
        benchmarkRepository = JdbcBenchmarkRepository(jdbcTemplate, transactionTemplate)
        itemResultRepository = JdbcBenchmarkItemResultRepository(jdbcTemplate, transactionTemplate)
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    /**
     * `@TestInstance(PER_CLASS)`でDBを全テスト間で共有する（コンテナ起動コストを1回に抑える、
     * `JdbcBenchmarkRepositoryIntegrationTest`と同じ理由）ため、Benchmark関連テーブルは
     * 各テスト開始前に空にする（`claimBatch`が前のテストの残留行まで拾ってしまうのを防ぐ）。
     */
    @BeforeEach
    fun cleanBenchmarkTables() {
        jdbcTemplate.update(
            """
            TRUNCATE TABLE benchmark_item_results, benchmark_targets, benchmark_metrics, benchmarks,
                golden_dataset_items, golden_datasets
            """.trimIndent(),
            MapSqlParameterSource(),
        )
    }

    private fun createApprovedPrompt(): PromptKey {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val semVer = SemVer(1, 0, 0)
        val (created, event) = Prompt.create(key, NewPromptVersion(semVer, PromptContent("body")), context)
        promptRepository.save(created, listOf(event))
        var prompt = promptRepository.findByKey(key)!!.submitForReview(semVer, validationPassed = true)
        promptRepository.save(prompt)
        prompt = promptRepository.findByKey(key)!!.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
        promptRepository.save(prompt)
        return key
    }

    private fun createDatasetWithItems(
        promptKey: PromptKey,
        itemCount: Int = 1,
    ): Pair<UUID, List<UUID>> {
        val items =
            (1..itemCount).map {
                GoldenDatasetItem(
                    itemId = UUID.randomUUID(),
                    parameters = emptyMap(),
                    context = emptyMap(),
                    expectedOutput = "expected",
                )
            }
        val dataset = GoldenDataset.create(promptKey, "dataset", null, items)
        datasetRepository.save(dataset)
        return dataset.datasetId to items.map { it.itemId }
    }

    /** RunningなBenchmarkを1 Target・1 Itemで用意し、materialize済みのresultIdを返す。 */
    private fun createRunningBenchmarkWithOneItem(): UUID {
        val promptKey = createApprovedPrompt()
        val (datasetId, itemIds) = createDatasetWithItems(promptKey)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(promptKey, datasetId, listOf(target), setOf(BenchmarkMetricType.Accuracy), 3)
        benchmarkRepository.save(benchmark)
        benchmarkRepository.save(benchmark.start())
        itemResultRepository.materialize(listOf(BenchmarkItemKey(target.targetId, itemIds.single())))
        return resultIdFor(target.targetId, itemIds.single())
    }

    private fun resultIdFor(
        targetId: UUID,
        itemId: UUID,
    ): UUID =
        jdbcTemplate.queryForObject(
            "SELECT result_id FROM benchmark_item_results WHERE target_id = :targetId AND item_id = :itemId",
            MapSqlParameterSource().addValue("targetId", targetId).addValue("itemId", itemId),
            UUID::class.java,
        )!!

    @Test
    fun `materializeは同じ組を再度渡しても重複挿入しない`() {
        val promptKey = createApprovedPrompt()
        val (datasetId, itemIds) = createDatasetWithItems(promptKey)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(promptKey, datasetId, listOf(target), setOf(BenchmarkMetricType.Accuracy), 3)
        benchmarkRepository.save(benchmark)
        val pairs = listOf(BenchmarkItemKey(target.targetId, itemIds.single()))

        itemResultRepository.materialize(pairs)
        itemResultRepository.materialize(pairs)

        val count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM benchmark_item_results WHERE target_id = :targetId",
                MapSqlParameterSource("targetId", target.targetId),
                Int::class.java,
            )
        count shouldBe 1
    }

    @Test
    fun `claimBatchはRunningなBenchmarkに属するPending項目のみ取得する`() {
        val resultId = createRunningBenchmarkWithOneItem()

        val claimed = itemResultRepository.claimBatch("instance-1", Duration.ofSeconds(30), 10)

        claimed.map { it.resultId } shouldBe listOf(resultId)
    }

    @Test
    fun `claimBatchはPendingなBenchmarkに属する項目は取得しない`() {
        val promptKey = createApprovedPrompt()
        val (datasetId, itemIds) = createDatasetWithItems(promptKey)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(promptKey, datasetId, listOf(target), setOf(BenchmarkMetricType.Accuracy), 3)
        benchmarkRepository.save(benchmark)
        // start()を呼ばない = BenchmarkはPendingのまま。
        itemResultRepository.materialize(listOf(BenchmarkItemKey(target.targetId, itemIds.single())))

        val claimed = itemResultRepository.claimBatch("instance-1", Duration.ofSeconds(30), 10)

        claimed shouldBe emptyList()
    }

    @Test
    fun `claimBatchはCancellingなBenchmarkに属する未Claim項目は新規に取得しない`() {
        // ADR-0035決定3: ワーカーは項目のClaim前に親Benchmarkの状態を確認し、Cancellingなら
        // その項目をClaimしない。
        val promptKey = createApprovedPrompt()
        val (datasetId, itemIds) = createDatasetWithItems(promptKey)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(promptKey, datasetId, listOf(target), setOf(BenchmarkMetricType.Accuracy), 3)
        benchmarkRepository.save(benchmark)
        val cancelling = benchmark.start().requestCancellation()
        benchmarkRepository.save(cancelling)
        itemResultRepository.materialize(listOf(BenchmarkItemKey(target.targetId, itemIds.single())))

        val claimed = itemResultRepository.claimBatch("instance-1", Duration.ofSeconds(30), 10)

        claimed shouldBe emptyList()
    }

    @Test
    fun `claimBatchはstale化したClaimed項目も再取得する`() {
        val resultId = createRunningBenchmarkWithOneItem()
        val shortClaimTimeout = Duration.ofSeconds(1)
        itemResultRepository.claimBatch("instance-a", shortClaimTimeout, 10)

        Thread.sleep(shortClaimTimeout.toMillis() + 2000)
        val reclaimed = itemResultRepository.claimBatch("instance-b", shortClaimTimeout, 10)

        reclaimed.map { it.resultId } shouldBe listOf(resultId)
    }

    @Test
    fun `markCompletedはフェンシングが効いていればスコアを保存する`() {
        val resultId = createRunningBenchmarkWithOneItem()
        itemResultRepository.claimBatch("instance-1", Duration.ofSeconds(30), 10)

        val owned =
            itemResultRepository.markCompleted(
                resultId,
                "instance-1",
                BenchmarkItemScores(accuracyScore = BigDecimal("0.7500")),
            )

        owned shouldBe true
        val row =
            jdbcTemplate.queryForMap(
                "SELECT status, accuracy_score FROM benchmark_item_results WHERE result_id = :id",
                MapSqlParameterSource("id", resultId),
            )
        row["status"] shouldBe "Completed"
        (row["accuracy_score"] as BigDecimal).compareTo(BigDecimal("0.75")) shouldBe 0
    }

    @Test
    fun `markFailedはフェンシングが効いていればerror_messageを保存しclaimedを解放する`() {
        val resultId = createRunningBenchmarkWithOneItem()
        itemResultRepository.claimBatch("instance-1", Duration.ofSeconds(30), 10)

        val owned = itemResultRepository.markFailed(resultId, "instance-1", "provider timeout")

        owned shouldBe true
        val row =
            jdbcTemplate.queryForMap(
                """
                SELECT status, error_message, claimed_at, claimed_by
                FROM benchmark_item_results WHERE result_id = :id
                """.trimIndent(),
                MapSqlParameterSource("id", resultId),
            )
        row["status"] shouldBe "Failed"
        row["error_message"] shouldBe "provider timeout"
        row["claimed_at"] shouldBe null
        row["claimed_by"] shouldBe null
    }

    /**
     * 本命（`docs/prompts/m2-4b-c.md`）: ワーカーAが項目をclaim → claim_timeout経過 →
     * ワーカーBが再claim → Aが確定（markCompleted）を試みる → 確定できないこと。
     * `OutboxRelayIntegrationTest`「claim_timeoutを過ぎて別インスタンスに再claimされた行を
     * 元インスタンスのmarkDispatchedは上書きしない」と同じ構造で、実際のPostgresに対して
     * 3段階Claimのフェンシングを再現する。
     */
    @Test
    fun `claim_timeoutを過ぎて別インスタンスに再claimされた項目を元インスタンスのmarkCompletedは上書きしない`() {
        val resultId = createRunningBenchmarkWithOneItem()
        val shortClaimTimeout = Duration.ofSeconds(1)

        val claimedByA = itemResultRepository.claimBatch("instance-a", shortClaimTimeout, 10)
        claimedByA.map { it.resultId } shouldBe listOf(resultId)

        Thread.sleep(shortClaimTimeout.toMillis() + 2000)

        val claimedByB = itemResultRepository.claimBatch("instance-b", shortClaimTimeout, 10)
        claimedByB.map { it.resultId } shouldBe listOf(resultId)

        // Aはクレームした時点のresultIdをまだ持っている（クラッシュしていなかった、または
        // 実行が遅延しただけのケースを模す）が、行の所有権はもうBにある。
        val owned =
            itemResultRepository.markCompleted(
                resultId,
                "instance-a",
                BenchmarkItemScores(accuracyScore = BigDecimal("1.0000")),
            )

        owned shouldBe false
        val row =
            jdbcTemplate.queryForMap(
                "SELECT claimed_by, status, accuracy_score FROM benchmark_item_results WHERE result_id = :id",
                MapSqlParameterSource("id", resultId),
            )
        row["claimed_by"] shouldBe "instance-b"
        row["status"] shouldBe "Claimed"
        row["accuracy_score"] shouldBe null
    }

    @Test
    fun `claim_timeoutを過ぎて別インスタンスに再claimされた項目を元インスタンスのmarkFailedは上書きしない`() {
        val resultId = createRunningBenchmarkWithOneItem()
        val shortClaimTimeout = Duration.ofSeconds(1)

        itemResultRepository.claimBatch("instance-a", shortClaimTimeout, 10)
        Thread.sleep(shortClaimTimeout.toMillis() + 2000)
        val claimedByB = itemResultRepository.claimBatch("instance-b", shortClaimTimeout, 10)
        claimedByB.map { it.resultId } shouldBe listOf(resultId)

        val owned = itemResultRepository.markFailed(resultId, "instance-a", "stale finalize attempt")

        owned shouldBe false
        val row =
            jdbcTemplate.queryForMap(
                "SELECT claimed_by, status FROM benchmark_item_results WHERE result_id = :id",
                MapSqlParameterSource("id", resultId),
            )
        row["claimed_by"] shouldBe "instance-b"
        row["status"] shouldBe "Claimed"
    }

    /**
     * 二重実行防止（`docs/prompts/m2-4b-c.md`「2つのワーカーが同じ項目を二重実行しない」、
     * 実プロバイダでは二重課金に直結する）の核となる`FOR UPDATE SKIP LOCKED`自体を検証する。
     */
    @Test
    fun `複数インスタンスが同時にclaimBatchしても同じ項目を二重にclaimしない`() {
        val promptKey = createApprovedPrompt()
        val itemCount = 20
        val (datasetId, itemIds) = createDatasetWithItems(promptKey, itemCount)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(promptKey, datasetId, listOf(target), setOf(BenchmarkMetricType.Accuracy), 1)
        benchmarkRepository.save(benchmark)
        benchmarkRepository.save(benchmark.start())
        itemResultRepository.materialize(itemIds.map { BenchmarkItemKey(target.targetId, it) })

        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        val claimedByInstance1 = mutableListOf<UUID>()
        val claimedByInstance2 = mutableListOf<UUID>()

        val task1 =
            executor.submit {
                startLatch.await()
                claimedByInstance1 +=
                    itemResultRepository.claimBatch("instance-a", Duration.ofSeconds(30), itemCount)
                        .map { it.resultId }
            }
        val task2 =
            executor.submit {
                startLatch.await()
                claimedByInstance2 +=
                    itemResultRepository.claimBatch("instance-b", Duration.ofSeconds(30), itemCount)
                        .map { it.resultId }
            }
        startLatch.countDown()
        task1.get(30, TimeUnit.SECONDS)
        task2.get(30, TimeUnit.SECONDS)
        executor.shutdown()

        val overlap = claimedByInstance1.intersect(claimedByInstance2.toSet())
        overlap shouldBe emptySet()
        (claimedByInstance1.size + claimedByInstance2.size) shouldBe itemCount
    }

    @Test
    fun `hasIncompleteはPendingまたはClaimedの項目が残っていればtrue`() {
        val promptKey = createApprovedPrompt()
        val (datasetId, itemIds) = createDatasetWithItems(promptKey, itemCount = 1)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(promptKey, datasetId, listOf(target), setOf(BenchmarkMetricType.Accuracy), 3)
        benchmarkRepository.save(benchmark)
        benchmarkRepository.save(benchmark.start())
        itemResultRepository.materialize(listOf(BenchmarkItemKey(target.targetId, itemIds.single())))

        itemResultRepository.hasIncomplete(benchmark.benchmarkId) shouldBe true
    }

    @Test
    fun `hasIncompleteは全項目がCompletedならfalse`() {
        val resultId = createRunningBenchmarkWithOneItem()
        val benchmarkId =
            jdbcTemplate.queryForObject(
                """
                SELECT b.benchmark_id FROM benchmarks b
                JOIN benchmark_targets t ON t.benchmark_id = b.benchmark_id
                JOIN benchmark_item_results r ON r.target_id = t.target_id
                WHERE r.result_id = :resultId
                """.trimIndent(),
                MapSqlParameterSource("resultId", resultId),
                UUID::class.java,
            )!!
        itemResultRepository.claimBatch("instance-1", Duration.ofSeconds(30), 10)
        itemResultRepository.markCompleted(resultId, "instance-1", BenchmarkItemScores(accuracyScore = BigDecimal.ONE))

        itemResultRepository.hasIncomplete(benchmarkId) shouldBe false
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
