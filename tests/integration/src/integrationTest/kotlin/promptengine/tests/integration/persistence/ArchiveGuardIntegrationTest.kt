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
import promptengine.domain.evaluation.ExecutionLogEntry
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.event.EventContext
import promptengine.domain.execution.Usage
import promptengine.domain.prompt.ArchiveEligibility
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcArchiveEligibilityRepository
import promptengine.infrastructure.persistence.JdbcExecutionLogRepository
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * `archive`ガードの`execution_logs`ベース化（Issue #48、ADR-0026決定5）を実PostgreSQLで検証する。
 *
 * `docs/prompts/p10b.md`のテスト要件「直近実行がある場合にarchiveが拒否され、無い場合に
 * 許可されること」に加え、カットオーバー以前のVersion（判断不能）が従来通りforce専用のまま
 * であることも明示的に確認する（P9bで固定した契約の維持）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchiveGuardIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var eligibilityRepository: JdbcArchiveEligibilityRepository
    private lateinit var executionLogRepository: JdbcExecutionLogRepository

    private val semVer = SemVer(1, 0, 0)
    private val now: Instant = Instant.parse("2026-08-09T00:00:00Z")
    private val cutoverAt: Instant = Instant.parse("2026-08-01T00:00:00Z")
    private val inactiveSince: Instant = now.minus(90, ChronoUnit.DAYS)

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
        eligibilityRepository = JdbcArchiveEligibilityRepository(jdbcTemplate)
        executionLogRepository = JdbcExecutionLogRepository(jdbcTemplate)
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    /** [versionCreatedAt]を指定してPromptを作る（`prompt_versions.created_at`を直接調整する）。 */
    private fun createPrompt(versionCreatedAt: Instant): PromptKey {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val content = PromptContent("---\npe: \"1\"\nkind: prompt\nkey: ${key.value}\n---\nhello")
        val eventContext = EventContext(actor = "user:test", traceId = "fixture-trace", occurredAt = Instant.EPOCH)
        val (prompt, event) = Prompt.create(key, NewPromptVersion(semVer, content), eventContext)
        promptRepository.save(prompt, listOf(event))

        jdbcTemplate.update(
            """
            UPDATE prompt_versions SET created_at = :createdAt
            WHERE prompt_id = (SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("createdAt", Timestamp.from(versionCreatedAt))
                .addValue("promptKey", key.value),
        )
        return key
    }

    private fun recordExecution(
        key: PromptKey,
        executedAt: Instant,
    ) {
        executionLogRepository.append(
            ExecutionLogEntry(
                eventId = UUID.randomUUID(),
                promptKey = key.value,
                semVer = semVer,
                callerSystem = "system",
                traceId = "trace-${UUID.randomUUID()}",
                latency = LatencyMs(250),
                usage = Usage(TokenCount(800), TokenCount(200)),
                cost = Cost(BigDecimal("0.4")),
                status = ExecutionStatus.SUCCESS,
                executedAt = executedAt,
            ),
        )
    }

    private fun evaluate(key: PromptKey): ArchiveEligibility =
        eligibilityRepository.evaluate(key, semVer, cutoverAt, inactiveSince)

    @Test
    fun `カットオーバー以降で判定窓に実行が無ければInactive（force無しでarchive可能）`() {
        val key = createPrompt(versionCreatedAt = cutoverAt.plus(1, ChronoUnit.DAYS))

        evaluate(key) shouldBe ArchiveEligibility.Inactive
    }

    @Test
    fun `カットオーバー以降で判定窓に実行があればRecentlyExecuted（archiveを拒否）`() {
        val key = createPrompt(versionCreatedAt = cutoverAt.plus(1, ChronoUnit.DAYS))
        recordExecution(key, executedAt = now.minus(1, ChronoUnit.DAYS))

        evaluate(key) shouldBe ArchiveEligibility.RecentlyExecuted
    }

    @Test
    fun `判定窓より古い実行しか無ければInactive`() {
        val key = createPrompt(versionCreatedAt = cutoverAt.plus(1, ChronoUnit.DAYS))
        recordExecution(key, executedAt = inactiveSince.minus(1, ChronoUnit.DAYS))

        evaluate(key) shouldBe ArchiveEligibility.Inactive
    }

    /**
     * P10b以前に作られたVersionは「実行記録が無い」ことから参照ゼロを結論できないため、
     * 恒久的にforce専用のまま（ADR-0026決定5で明示的に受け入れた限界）。
     */
    @Test
    fun `カットオーバー以前に作られたVersionは実行記録の有無によらずPreCutover`() {
        val withoutExecution = createPrompt(versionCreatedAt = cutoverAt.minus(1, ChronoUnit.DAYS))
        val withExecution = createPrompt(versionCreatedAt = cutoverAt.minus(1, ChronoUnit.DAYS))
        recordExecution(withExecution, executedAt = now.minus(1, ChronoUnit.DAYS))

        evaluate(withoutExecution) shouldBe ArchiveEligibility.PreCutover
        evaluate(withExecution) shouldBe ArchiveEligibility.PreCutover
    }

    @Test
    fun `存在しないPromptはVersionNotFound`() {
        eligibilityRepository.evaluate(
            PromptKey("integration-test/never-created"),
            semVer,
            cutoverAt,
            inactiveSince,
        ) shouldBe ArchiveEligibility.VersionNotFound
    }

    @Test
    fun `存在するPromptでもVersionが違えばVersionNotFound`() {
        val key = createPrompt(versionCreatedAt = cutoverAt.plus(1, ChronoUnit.DAYS))

        eligibilityRepository.evaluate(key, SemVer(9, 9, 9), cutoverAt, inactiveSince) shouldBe
            ArchiveEligibility.VersionNotFound
    }

    @Test
    fun `hasExecutionSinceは判定窓の内外を正しく区別する`() {
        val key = createPrompt(versionCreatedAt = cutoverAt.plus(1, ChronoUnit.DAYS))
        recordExecution(key, executedAt = now.minus(10, ChronoUnit.DAYS))

        executionLogRepository.hasExecutionSince(key, semVer, now.minus(30, ChronoUnit.DAYS)) shouldBe true
        executionLogRepository.hasExecutionSince(key, semVer, now.minus(5, ChronoUnit.DAYS)) shouldBe false
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
