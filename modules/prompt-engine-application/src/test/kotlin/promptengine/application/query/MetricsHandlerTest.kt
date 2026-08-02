package promptengine.application.query

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.metrics.MetricsRepository
import promptengine.domain.metrics.MetricsSummary
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant

class MetricsHandlerTest {
    private class FakeMetricsRepository : MetricsRepository {
        override fun summarize(
            promptKey: PromptKey,
            from: Instant,
            to: Instant,
        ): MetricsSummary =
            MetricsSummary(
                promptKey = promptKey,
                from = from,
                to = to,
                executionCount = 0,
                successCount = 0,
                totalInputTokens = TokenCount(0),
                totalOutputTokens = TokenCount(0),
                totalCost = Cost(BigDecimal.ZERO),
                averageLatency = LatencyMs(0),
            )
    }

    @Test
    fun `delegates to MetricsRepository`() {
        val handler = MetricsHandler(FakeMetricsRepository())
        val key = PromptKey("team/greeting")

        val summary = handler.handle(MetricsQuery(key, Instant.EPOCH, Instant.now()))

        summary.promptKey shouldBe key
    }
}
