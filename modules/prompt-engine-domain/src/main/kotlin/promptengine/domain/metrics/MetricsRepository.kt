package promptengine.domain.metrics

import promptengine.domain.prompt.PromptKey
import java.time.Instant

/**
 * `GET /metrics/prompts/{key}`（設計書§13.1）を支えるRepository（ADR-0017）。
 */
interface MetricsRepository {
    /** [promptKey]の[from]〜[to]区間における`execution_logs`集計を返す。対象行が無い場合も0値の[MetricsSummary]を返す。 */
    fun summarize(
        promptKey: PromptKey,
        from: Instant,
        to: Instant,
    ): MetricsSummary
}
