package promptengine.infrastructure.audit

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditRecord
import promptengine.domain.pipeline.PipelineMode
import java.time.Instant

private const val SECRET_VALUE = "sk-live-do-not-leak-98765"

/**
 * [Slf4jAuditFailureHandler]の単体テスト（ADR-0015決定8）。
 *
 * [AuditRecord]自体は生のprompt/response内容を保持しない構造的な最小集合だが、
 * `cause`（インフラ層由来の例外、DB接続情報等を含みうる）のメッセージが誤って
 * 組立文字列へ混入していないこと（ログ本文には[AuditRecord]のフィールドのみを載せる
 * 契約）を、実際のログ出力を[ListAppender]で捕捉して検証する。
 */
class Slf4jAuditFailureHandlerTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(Slf4jAuditFailureHandler::class.java) as Logger
        appender = ListAppender()
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    @Test
    fun `causeのmessageに秘密情報が含まれていてもログの組立文字列には現れない`() {
        val record =
            AuditRecord(
                traceId = "trace-secret-leak-check",
                promptKey = "support/faq",
                mode = PipelineMode.FULL_EXECUTION,
                stageDurationsMs = mapOf("Load" to 1L),
                outcome = AuditOutcome.Failure("EXECUTION_FAILED"),
                occurredAt = Instant.EPOCH,
            )
        val cause = IllegalStateException("db connection failed: password=$SECRET_VALUE")

        Slf4jAuditFailureHandler().handle(record, cause)

        appender.list.shouldNotBeEmpty()
        val formattedMessage = appender.list.single().formattedMessage
        formattedMessage shouldNotContain SECRET_VALUE
        formattedMessage shouldContain "trace-secret-leak-check"
        formattedMessage shouldContain "support/faq"
        formattedMessage shouldContain "FULL_EXECUTION"
        formattedMessage shouldContain "EXECUTION_FAILED"
        formattedMessage shouldContain "IllegalStateException"
    }

    @Test
    fun `Success outcomeはoutcomeラベルにSuccessとだけ記録される`() {
        val record =
            AuditRecord(
                traceId = "trace-success-outcome",
                promptKey = null,
                mode = PipelineMode.RENDER_ONLY,
                stageDurationsMs = emptyMap(),
                outcome = AuditOutcome.Success,
                occurredAt = Instant.EPOCH,
            )

        Slf4jAuditFailureHandler().handle(record, RuntimeException("unavailable"))

        val formattedMessage = appender.list.single().formattedMessage
        formattedMessage shouldContain "outcome=Success"
        formattedMessage.contains("errorCode") shouldBe false
    }
}
