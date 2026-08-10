package promptengine.domain.evaluation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.execution.Usage
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PromptExecutionSummaryTest {
    private fun summary(
        promptKey: String = "support/faq",
        retryCount: Int = 0,
        callerSystem: String = "system",
        inputTokens: Int = 100,
        outputTokens: Int = 20,
    ) = PromptExecutionSummary(
        eventId = UUID.randomUUID(),
        promptKey = promptKey,
        semVer = SemVer(1, 0, 0),
        latency = LatencyMs(120),
        usage = Usage(TokenCount(inputTokens), TokenCount(outputTokens)),
        costPerToken = Cost(BigDecimal("0.001")),
        status = ExecutionStatus.SUCCESS,
        retryCount = retryCount,
        callerSystem = callerSystem,
        traceId = "trace-1",
        occurredAt = Instant.EPOCH,
    )

    @Test
    fun `promptKeyが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { summary(promptKey = " ") }
    }

    @Test
    fun `retryCountが負の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { summary(retryCount = -1) }
    }

    @Test
    fun `callerSystemが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { summary(callerSystem = "") }
    }

    @Test
    fun `totalTokensは入力トークンと出力トークンの合計`() {
        summary(inputTokens = 100, outputTokens = 20).totalTokens shouldBe 120
    }

    @Test
    fun `トークン数が0でもtotalTokensは0として構築できる`() {
        summary(inputTokens = 0, outputTokens = 0).totalTokens shouldBe 0
    }
}
