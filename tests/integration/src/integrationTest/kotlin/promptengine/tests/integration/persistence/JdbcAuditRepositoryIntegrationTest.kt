package promptengine.tests.integration.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRecord
import promptengine.domain.event.EventContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcAuditRepository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcAuditRepository]のTestcontainers(PostgreSQL 16)統合テスト（ADR-0017、Issue #35クローズ）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAuditRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var auditRepository: JdbcAuditRepository
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

        val jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
        val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
        auditRepository = JdbcAuditRepository(jdbcTemplate, jacksonObjectMapper())
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun wrap(key: String): String = "---\npe: \"1\"\nkind: prompt\nkey: $key\n---\nhello"

    private fun createPrompt(key: PromptKey) {
        val newVersion = NewPromptVersion(SemVer(1, 0, 0), PromptContent(wrap(key.value)))
        val (prompt, event) = Prompt.create(key, newVersion, eventContext)
        promptRepository.save(prompt, listOf(event))
    }

    @Test
    fun `appendしたPipeline実行記録はaudit_logsへPipelineExecutionとして永続化される`() {
        val key = uniqueKey()
        createPrompt(key)
        val record =
            AuditRecord(
                traceId = "trace-append-1",
                promptKey = key.value,
                mode = PipelineMode.FULL_EXECUTION,
                stageDurationsMs = mapOf("Load" to 5L),
                outcome = AuditOutcome.Success,
                occurredAt = Instant.now(),
            )

        auditRepository.append(record)

        val page = auditRepository.search(AuditQuery(aggregateId = key.value))
        page.items.single().aggregateType shouldBe "PipelineExecution"
        page.items.single().traceId shouldBe "trace-append-1"
    }

    @Test
    fun `recordした行はsearchで見つかる`() {
        val key = uniqueKey()
        createPrompt(key)
        val entry =
            AuditLogEntry(
                auditId = UUID.randomUUID(),
                aggregateType = "Prompt",
                aggregateId = key.value,
                action = "Published",
                actor = "user:a",
                payload = "{}",
                traceId = "trace-record-1",
                occurredAt = Instant.now(),
            )

        auditRepository.record(entry)

        val page = auditRepository.search(AuditQuery(aggregateId = key.value))
        page.items.single().action shouldBe "Published"
        page.items.single().actor shouldBe "user:a"
    }

    @Test
    fun `searchはactorで絞り込める`() {
        // 同一クラス内の他テストと同じPostgresコンテナを共有する（TestInstance.Lifecycle.PER_CLASS）
        // ため、actorのみでの絞り込みは他テストのレコードも拾ってしまう。aggregateIdも合わせて
        // 指定し、このテスト自身が作った行だけを対象にする。
        val key = uniqueKey()
        createPrompt(key)
        auditRepository.record(logEntry(key, actor = "user:a"))
        auditRepository.record(logEntry(key, actor = "user:b"))

        val page = auditRepository.search(AuditQuery(aggregateId = key.value, actor = "user:a"))

        page.items.map { it.actor } shouldBe listOf("user:a")
    }

    @Test
    fun `promptKeyがnullのappend記録もsearchで見つかる`() {
        // JdbcAuditRepository.appendはpromptKey nullの場合prompts行に紐付かないNIL_PROMPT_IDで
        // 書き込む。LEFT JOINでない実装だとこれらの行はsearchから恒久的に見えなくなる
        // （CodeRabbitレビュー指摘、書き込みは成功するのに検索できない状態）。
        val traceId = "trace-null-promptkey-${UUID.randomUUID()}"
        val record =
            AuditRecord(
                traceId = traceId,
                promptKey = null,
                mode = PipelineMode.RENDER_ONLY,
                stageDurationsMs = mapOf("Load" to 3L),
                outcome = AuditOutcome.Success,
                occurredAt = Instant.now(),
            )

        auditRepository.append(record)

        val page = auditRepository.search(AuditQuery(actor = "system"))
        page.items.map { it.traceId } shouldContain traceId
    }

    private fun logEntry(
        key: PromptKey,
        actor: String,
    ) = AuditLogEntry(
        auditId = UUID.randomUUID(),
        aggregateType = "Prompt",
        aggregateId = key.value,
        action = "Published",
        actor = actor,
        payload = "{}",
        traceId = "trace-${UUID.randomUUID()}",
        occurredAt = Instant.now(),
    )

    private fun uniqueKey(): PromptKey = PromptKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
