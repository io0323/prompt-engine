package promptengine.domain.metrics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant

class MetricsSummaryTest {
    private fun summary(
        executionCount: Long,
        successCount: Long,
    ) = MetricsSummary(
        promptKey = PromptKey("support/faq"),
        from = Instant.EPOCH,
        to = Instant.EPOCH.plusSeconds(60),
        executionCount = executionCount,
        successCount = successCount,
        totalInputTokens = TokenCount(100),
        totalOutputTokens = TokenCount(50),
        totalCost = Cost(BigDecimal("0.01")),
        averageLatency = LatencyMs(200),
    )

    @Test
    fun `executionCountが0ならsuccessRateは0`() {
        summary(executionCount = 0, successCount = 0).successRate shouldBe 0.0
    }

    @Test
    fun `successRateはsuccessCount executionCountの比率`() {
        summary(executionCount = 4, successCount = 3).successRate shouldBe 0.75
    }

    @Test
    fun `全件成功ならsuccessRateは1`() {
        summary(executionCount = 2, successCount = 2).successRate shouldBe 1.0
    }
}
