package promptengine.application.view

import promptengine.application.query.DependenciesQuery
import promptengine.application.query.DependencyDirection
import promptengine.application.query.MetricsQuery
import java.time.Instant

/**
 * `DependencyController`・`MetricsController`が使うQueryを構築する（P9c）。
 *
 * [DomainValueFactory]のKDoc参照（`prompt-engine-interface`がdomain型を直接構築できない理由）。
 */
object QueryFactory {
    /** `GET /prompts/{namespace}/{name}/dependencies?direction=`用の[DependenciesQuery]を構築する。 */
    fun dependenciesQuery(
        key: String,
        direction: String,
    ): DependenciesQuery =
        DependenciesQuery(DomainValueFactory.promptKey(key), DependencyDirection.valueOf(direction.uppercase()))

    /** `GET /metrics/prompts/{namespace}/{name}?from=&to=`用の[MetricsQuery]を構築する。 */
    fun metricsQuery(
        key: String,
        from: Instant,
        to: Instant,
    ): MetricsQuery = MetricsQuery(DomainValueFactory.promptKey(key), from, to)
}
