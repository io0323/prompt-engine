package promptengine.interfaces.rest

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import promptengine.application.query.MetricsHandler
import promptengine.application.view.DomainValueFactory
import promptengine.application.view.QueryFactory
import promptengine.application.view.handleView
import promptengine.interfaces.dto.MetricsSummaryDto
import java.time.Instant

/** `GET /metrics/prompts/{namespace}/{name}?from=&to=`（設計書§13.1、[PromptController]のKDoc参照）。 */
@RestController
@RequestMapping("/api/v1/metrics/prompts/{namespace}/{name}")
class MetricsController(private val metricsHandler: MetricsHandler) {
    @GetMapping
    @PreAuthorize("hasAuthority('prompt:read')")
    fun summarize(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant,
    ): MetricsSummaryDto {
        // `JdbcMetricsRepository`は`BETWEEN :from AND :to`をそのまま発行するため、from>toだと
        // クエリ自体は成立し0件集計を返してしまう。境界で明示的に拒否する。
        require(!from.isAfter(to)) { "from must not be after to: from=$from, to=$to" }
        val key = DomainValueFactory.promptKeyText(namespace, name)
        val view = metricsHandler.handleView(QueryFactory.metricsQuery(key, from, to))
        return MetricsSummaryDto(
            view.promptKey,
            view.from,
            view.to,
            view.executionCount,
            view.successCount,
            view.successRate,
            view.totalInputTokens,
            view.totalOutputTokens,
            view.totalCost,
            view.averageLatencyMs,
        )
    }
}
