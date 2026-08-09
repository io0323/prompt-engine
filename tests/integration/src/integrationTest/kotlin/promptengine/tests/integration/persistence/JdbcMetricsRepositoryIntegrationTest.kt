package promptengine.tests.integration.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcMetricsRepository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcMetricsRepository]のTestcontainers(PostgreSQL 16)統合テスト（ADR-0017）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcMetricsRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var metricsRepository: JdbcMetricsRepository
    private lateinit var promptRepository: EventStorePromptRepository

    private val eventContext = EventContext(actor = "user:test", traceId = "fixture-trace", occurredAt = Instant.EPOCH)

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
        metricsRepository = JdbcMetricsRepository(jdbcTemplate)
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun wrap(key: String): String = "---\npe: \"1\"\nkind: prompt\nkey: $key\n---\nhello"

    private fun createPromptVersionId(key: PromptKey): UUID {
        val (created, createdEvent) =
            Prompt.create(key, NewPromptVersion(SemVer(1, 0, 0), PromptContent(wrap(key.value))), eventContext)
        promptRepository.save(created, listOf(createdEvent))
        return jdbcTemplate.queryForObject(
            """
            SELECT version_id FROM prompt_versions
            WHERE prompt_id = (SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey)
            """.trimIndent(),
            MapSqlParameterSource().addValue("promptKey", key.value),
            UUID::class.java,
        )!!
    }

    private fun insertExecutionLog(
        versionId: UUID,
        status: String,
        inputTokens: Int,
        outputTokens: Int,
        cost: BigDecimal,
        latencyMs: Int,
        executedAt: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO execution_logs
                (execution_id, version_id, caller_system, trace_id, latency_ms, input_tokens, output_tokens,
                 cost, status, executed_at, event_id)
            VALUES (:id, :versionId, 'test', :traceId, :latencyMs, :inputTokens, :outputTokens,
                    :cost, :status, :executedAt, :eventId)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                // P10b（V13）で execution_logs へ event_id UNIQUE NOT NULL を追加した
                // （購読側冪等性、ADR-0025決定8）。本テストは直接INSERTするため自前で採番する。
                .addValue("eventId", UUID.randomUUID())
                .addValue("versionId", versionId)
                .addValue("traceId", "trace-${UUID.randomUUID()}")
                .addValue("latencyMs", latencyMs)
                .addValue("inputTokens", inputTokens)
                .addValue("outputTokens", outputTokens)
                .addValue("cost", cost)
                .addValue("status", status)
                .addValue("executedAt", Timestamp.from(executedAt)),
        )
    }

    @Test
    fun `summarizeは区間内のexecution_logsを集計する`() {
        val key = uniqueKey()
        val versionId = createPromptVersionId(key)
        val now = Instant.now()
        insertExecutionLog(versionId, "success", 100, 50, BigDecimal("0.01"), 200, now)
        insertExecutionLog(versionId, "success", 200, 80, BigDecimal("0.02"), 400, now)
        insertExecutionLog(versionId, "failure", 50, 0, BigDecimal("0.00"), 100, now)

        val summary = metricsRepository.summarize(key, now.minusSeconds(60), now.plusSeconds(60))

        summary.executionCount shouldBe 3L
        summary.successCount shouldBe 2L
        summary.totalInputTokens.value shouldBe 350
        summary.totalOutputTokens.value shouldBe 130
        summary.successRate shouldBe (2.0 / 3.0)
        // SUM(0.01 + 0.02 + 0.00) = 0.03。BigDecimalのスケール差異を避けるためcompareToで比較する。
        summary.totalCost.value.compareTo(BigDecimal("0.03")) shouldBe 0
        // AVG(200, 400, 100) = 233.33...。rs.getLong はJDBCのNUMERIC→long変換で小数部を切り捨てる。
        summary.averageLatency.value shouldBe 233L
    }

    @Test
    fun `summarizeは区間外の行を含めない`() {
        val key = uniqueKey()
        val versionId = createPromptVersionId(key)
        val now = Instant.now()
        insertExecutionLog(versionId, "success", 100, 50, BigDecimal("0.01"), 200, now.minusSeconds(1000))

        val summary = metricsRepository.summarize(key, now.minusSeconds(60), now.plusSeconds(60))

        summary.executionCount shouldBe 0L
    }

    @Test
    fun `対象行が無ければ0値のMetricsSummaryを返す`() {
        val key = uniqueKey()
        createPromptVersionId(key)
        val now = Instant.now()

        val summary = metricsRepository.summarize(key, now.minusSeconds(60), now.plusSeconds(60))

        summary.executionCount shouldBe 0L
        summary.successRate shouldBe 0.0
    }

    private fun uniqueKey(): PromptKey = PromptKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
