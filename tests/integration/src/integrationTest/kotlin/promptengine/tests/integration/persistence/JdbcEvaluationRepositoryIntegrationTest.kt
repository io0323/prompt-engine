package promptengine.tests.integration.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
import promptengine.domain.dlq.DeadLetterEntry
import promptengine.domain.evaluation.EvaluationRecord
import promptengine.domain.evaluation.ExecutionLogEntry
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.event.EventContext
import promptengine.domain.execution.Usage
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcDeadLetterQueueRepository
import promptengine.infrastructure.persistence.JdbcEvaluationRepository
import promptengine.infrastructure.persistence.JdbcExecutionLogRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * P10bで追加した3つのRepository（`evaluation_records` / `execution_logs` / `dead_letter_queue`）の
 * SQL・冪等キーを実PostgreSQLで検証する（ADR-0026、V13マイグレーション）。
 *
 * Broker経由の経路全体は`EventSubscriberIntegrationTest`が見る。ここではRepository単体の
 * 契約（`ON CONFLICT`の効き方、業務キー→サロゲートUUIDの解決、DLQのUPSERT）に絞る。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcEvaluationRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var evaluationRepository: JdbcEvaluationRepository
    private lateinit var executionLogRepository: JdbcExecutionLogRepository
    private lateinit var deadLetterQueueRepository: JdbcDeadLetterQueueRepository

    private val semVer = SemVer(1, 0, 0)

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
        evaluationRepository = JdbcEvaluationRepository(jdbcTemplate)
        executionLogRepository = JdbcExecutionLogRepository(jdbcTemplate)
        deadLetterQueueRepository = JdbcDeadLetterQueueRepository(jdbcTemplate)
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun createPrompt(): PromptKey {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val content = PromptContent("---\npe: \"1\"\nkind: prompt\nkey: ${key.value}\n---\nhello")
        val eventContext = EventContext(actor = "user:test", traceId = "fixture-trace", occurredAt = Instant.EPOCH)
        val (prompt, event) = Prompt.create(key, NewPromptVersion(semVer, content), eventContext)
        promptRepository.save(prompt, listOf(event))
        return key
    }

    private fun evaluationRecord(
        key: PromptKey,
        eventId: UUID,
        metricType: String,
    ) = EvaluationRecord(
        eventId = eventId,
        promptKey = key.value,
        semVer = semVer,
        metricType = metricType,
        score = BigDecimal("250"),
        method = "measured",
        sampleRef = "trace-1",
        evaluatedAt = Instant.parse("2026-08-09T00:00:00Z"),
    )

    private fun executionLogEntry(
        key: PromptKey,
        eventId: UUID,
    ) = ExecutionLogEntry(
        eventId = eventId,
        promptKey = key.value,
        semVer = semVer,
        callerSystem = "system",
        traceId = "trace-1",
        latency = LatencyMs(250),
        usage = Usage(TokenCount(800), TokenCount(200)),
        cost = Cost(BigDecimal("0.4")),
        status = ExecutionStatus.SUCCESS,
        executedAt = Instant.parse("2026-08-09T00:00:00Z"),
    )

    private fun countEvaluations(eventId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM evaluation_records WHERE event_id = :eventId",
            MapSqlParameterSource("eventId", eventId),
            Long::class.java,
        ) ?: 0L

    @Test
    fun `1つのイベントから複数指標の行を書ける（冪等キーはevent_idとmetric_typeの複合）`() {
        val key = createPrompt()
        val eventId = UUID.randomUUID()

        val inserted =
            evaluationRepository.saveAll(
                listOf(
                    evaluationRecord(key, eventId, "Latency"),
                    evaluationRecord(key, eventId, "TokenUsage"),
                    evaluationRecord(key, eventId, "Cost"),
                ),
            )

        inserted shouldBe 3
        withClue("event_id単独のUNIQUEだと2件目以降が捨てられてしまう（V13で複合UNIQUEにした理由）") {
            countEvaluations(eventId) shouldBe 3L
        }
    }

    @Test
    fun `同一イベントの再配信では評価結果が二重に書かれない`() {
        val key = createPrompt()
        val eventId = UUID.randomUUID()
        val records = listOf(evaluationRecord(key, eventId, "Latency"), evaluationRecord(key, eventId, "Cost"))
        evaluationRepository.saveAll(records)

        val secondAttempt = evaluationRepository.saveAll(records)

        secondAttempt shouldBe 0
        countEvaluations(eventId) shouldBe 2L
    }

    @Test
    fun `空リストの保存は問い合わせを行わず0件を返す`() {
        evaluationRepository.saveAll(emptyList()) shouldBe 0
    }

    @Test
    fun `存在しないVersionへの評価保存は失敗する（壊れたイベントを黙って捨てない）`() {
        val record =
            EvaluationRecord(
                eventId = UUID.randomUUID(),
                promptKey = "integration-test/never-created",
                semVer = semVer,
                metricType = "Latency",
                score = BigDecimal.ONE,
                method = "measured",
                sampleRef = null,
                evaluatedAt = Instant.now(),
            )

        shouldThrow<IllegalStateException> { evaluationRepository.saveAll(listOf(record)) }
    }

    @Test
    fun `execution_logsのappendは初回true 再配信falseを返す`() {
        val key = createPrompt()
        val eventId = UUID.randomUUID()

        executionLogRepository.append(executionLogEntry(key, eventId)) shouldBe true
        executionLogRepository.append(executionLogEntry(key, eventId)) shouldBe false
    }

    @Test
    fun `execution_logsは業務キーからversion_idを解決して書く`() {
        val key = createPrompt()
        val eventId = UUID.randomUUID()
        executionLogRepository.append(executionLogEntry(key, eventId))

        val resolved =
            jdbcTemplate.queryForObject(
                """
                SELECT p.prompt_key
                FROM execution_logs el
                JOIN prompt_versions pv ON pv.version_id = el.version_id
                JOIN prompts p ON p.prompt_id = pv.prompt_id
                WHERE el.event_id = :eventId
                """.trimIndent(),
                MapSqlParameterSource("eventId", eventId),
                String::class.java,
            )

        resolved shouldBe key.value
    }

    @Test
    fun `存在しないVersionへのexecution_logs書き込みは失敗する`() {
        val entry =
            ExecutionLogEntry(
                eventId = UUID.randomUUID(),
                promptKey = "integration-test/never-created",
                semVer = semVer,
                callerSystem = "system",
                traceId = "trace-1",
                latency = LatencyMs(1),
                usage = Usage(TokenCount(1), TokenCount(1)),
                cost = Cost(BigDecimal.ZERO),
                status = ExecutionStatus.SUCCESS,
                executedAt = Instant.now(),
            )

        shouldThrow<IllegalStateException> { executionLogRepository.append(entry) }
    }

    @Test
    fun `DLQは同じevent_idとsubscriberの再退避でretry_countを加算する`() {
        val eventId = UUID.randomUUID()
        val entry =
            DeadLetterEntry(
                eventId = eventId,
                eventType = "PromptExecuted",
                subscriberName = "pe-audit-engine",
                payload = """{"a":1}""",
                failureReason = "IllegalStateException",
                failedAt = Instant.parse("2026-08-09T00:00:00Z"),
            )

        deadLetterQueueRepository.enqueue(entry)
        deadLetterQueueRepository.enqueue(entry.copy(failedAt = Instant.parse("2026-08-09T01:00:00Z")))

        val row =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) || ':' || MAX(retry_count) FROM dead_letter_queue WHERE event_id = :eventId",
                MapSqlParameterSource("eventId", eventId),
                String::class.java,
            )

        row shouldBe "1:1"
    }

    @Test
    fun `eventIdがnullの退避は失敗の都度1行ずつ積まれる`() {
        val before = countNullEventIdRows("pipeline-audit-stage")
        val entry =
            DeadLetterEntry(
                eventId = null,
                eventType = "PipelineAudit",
                subscriberName = "pipeline-audit-stage",
                payload = """{"traceId":"t"}""",
                failureReason = "IllegalStateException",
                failedAt = Instant.now(),
            )

        deadLetterQueueRepository.enqueue(entry)
        deadLetterQueueRepository.enqueue(entry)

        // PostgreSQLのUNIQUE制約はNULLを互いに異なる値として扱うため、この経路はUPSERTされない。
        countNullEventIdRows("pipeline-audit-stage") shouldBe before + 2
    }

    @Test
    fun `pendingCountは未処理の退避件数を返す`() {
        val before = deadLetterQueueRepository.pendingCount()

        deadLetterQueueRepository.enqueue(
            DeadLetterEntry(
                eventId = UUID.randomUUID(),
                eventType = "PromptExecuted",
                subscriberName = "pe-execution-log",
                payload = "{}",
                failureReason = "IllegalStateException",
                failedAt = Instant.now(),
            ),
        )

        deadLetterQueueRepository.pendingCount() shouldBe before + 1
    }

    /** DLQ書き込み自体が失敗しても例外を投げない契約（本流を巻き込まないため）。 */
    @Test
    fun `payloadがJSONとして不正でも呼出元へ例外を伝播させない`() {
        val before = deadLetterQueueRepository.pendingCount()

        deadLetterQueueRepository.enqueue(
            DeadLetterEntry(
                eventId = UUID.randomUUID(),
                eventType = "PromptExecuted",
                subscriberName = "pe-audit-engine",
                payload = "<<<not json>>>",
                failureReason = "IllegalStateException",
                failedAt = Instant.now(),
            ),
        )

        deadLetterQueueRepository.pendingCount() shouldBe before
    }

    private fun countNullEventIdRows(subscriberName: String): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM dead_letter_queue WHERE event_id IS NULL AND subscriber_name = :name",
            MapSqlParameterSource("name", subscriberName),
            Long::class.java,
        ) ?: 0L

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
