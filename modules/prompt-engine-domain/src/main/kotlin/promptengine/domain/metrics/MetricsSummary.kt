package promptengine.domain.metrics

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount
import java.time.Instant

/**
 * `GET /metrics/prompts/{key}`（設計書§13.1）が返す`execution_logs`（§12）の集計結果（ADR-0017）。
 */
data class MetricsSummary(
    val promptKey: PromptKey,
    val from: Instant,
    val to: Instant,
    val executionCount: Long,
    val successCount: Long,
    val totalInputTokens: TokenCount,
    val totalOutputTokens: TokenCount,
    val totalCost: Cost,
    val averageLatency: LatencyMs,
) {
    val successRate: Double
        get() = if (executionCount == 0L) 0.0 else successCount.toDouble() / executionCount.toDouble()
}
