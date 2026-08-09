package promptengine.infrastructure.evaluation

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.evaluation.PromptExecutionSummary
import promptengine.domain.execution.Usage
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 評価器の失敗ログ（ADR-0026決定3）。`Slf4jAuditFailureHandlerTest`と同じ観点で、
 * `Throwable`オブジェクトがログイベントへ添付されないことを固定する。
 */
class Slf4jEvaluationRuleFailureHandlerTest {
    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(Slf4jEvaluationRuleFailureHandler::class.java) as Logger
        logAppender = ListAppender()
        logAppender.start()
        logger.addAppender(logAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(logAppender)
    }

    private fun summary() =
        PromptExecutionSummary(
            eventId = UUID.randomUUID(),
            promptKey = "support/faq",
            semVer = SemVer(1, 0, 0),
            latency = LatencyMs(250),
            usage = Usage(TokenCount(800), TokenCount(200)),
            costPerToken = Cost(BigDecimal("0.0004")),
            status = ExecutionStatus.SUCCESS,
            retryCount = 0,
            callerSystem = "system",
            traceId = "trace-1",
            occurredAt = Instant.EPOCH,
        )

    @Test
    fun `失敗した評価器の指標名と実行の識別子を構造化して記録する`() {
        Slf4jEvaluationRuleFailureHandler().handle("Latency", summary(), IllegalStateException("boom"))

        val event = logAppender.list.single()
        val message = event.formattedMessage
        message shouldContain "evaluation_rule_failed"
        message shouldContain "metricType=Latency"
        message shouldContain "promptKey=support/faq"
        message shouldContain "traceId=trace-1"
        message shouldContain "cause=IllegalStateException"
    }

    /**
     * SLF4Jは末尾引数がThrowableの場合、メッセージに現れなくても`cause.message`と
     * スタックトレースをログイベントへ添付する。Plugin由来の評価器が例外メッセージに
     * 入力値を含める可能性を排除するため、Throwable自体を渡さない契約を固定する。
     */
    @Test
    fun `Throwableオブジェクトはログイベントへ添付しない`() {
        val secretishMessage = "failed for value sk-live-REAL-SECRET-VALUE"

        Slf4jEvaluationRuleFailureHandler().handle("Cost", summary(), IllegalStateException(secretishMessage))

        val event = logAppender.list.single()
        event.throwableProxy shouldBe null
        event.formattedMessage shouldNotContain "sk-live-REAL-SECRET-VALUE"
    }
}
