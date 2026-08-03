package promptengine.application.query

import promptengine.domain.metrics.MetricsRepository
import promptengine.domain.metrics.MetricsSummary
import promptengine.domain.prompt.PromptKey
import java.time.Instant

/** `GET /metrics/prompts/{key}?from=&to=`（設計書§13.1）。 */
data class MetricsQuery(val key: PromptKey, val from: Instant, val to: Instant)

class MetricsHandler(
    private val metricsRepository: MetricsRepository,
) {
    fun handle(query: MetricsQuery): MetricsSummary = metricsRepository.summarize(query.key, query.from, query.to)
}
