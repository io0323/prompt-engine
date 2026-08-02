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
        executor = JdbcIdempotentCommandExecutor(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun `executeInTransaction does not re-run command on retry with same key and fingerprint`() {
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
    fun `executeInTransaction throws IdempotencyKeyConflictException for same key with different fingerprint`() {
        val key = uniqueKey()
        executor.executeInTransaction(key, "fingerprint-a", DummyResult::class.java) { DummyResult(1) }

        shouldThrow<IdempotencyKeyConflictException> {
            executor.executeInTransaction(key, "fingerprint-b", DummyResult::class.java) { DummyResult(2) }
        }
    }

    @Test
    fun `executeLongRunning runs operation outside the reservation transaction and dedups on retry`() {
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
    fun `retry while a key is still IN_PROGRESS throws IdempotencyKeyInProgressException`() {
        val key = uniqueKey()
        reserveInProgress(key, "fingerprint-a")

        shouldThrow<IdempotencyKeyInProgressException> {
            executor.executeInTransaction(key, "fingerprint-a", DummyResult::class.java) { DummyResult(1) }
        }
    }

    /** `IN_PROGRESS`のまま完了しなかったキーを直接作る（executorの完了処理を経由しない）。 */
    private fun reserveInProgress(
        key: String,
        fingerprint: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO idempotency_keys (idempotency_key, request_fingerprint, status, created_at)
            VALUES (:key, :fingerprint, 'IN_PROGRESS', :createdAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("fingerprint", fingerprint)
                .addValue("createdAt", Timestamp.from(Instant.now())),
        )
    }

    private fun uniqueKey(): String = "integration-test-${UUID.randomUUID()}"

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
