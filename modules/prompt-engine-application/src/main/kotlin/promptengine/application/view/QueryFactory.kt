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
    fun dependenciesQuery(
        key: String,
        direction: String,
    ): DependenciesQuery =
        DependenciesQuery(DomainValueFactory.promptKey(key), DependencyDirection.valueOf(direction.uppercase()))

    fun metricsQuery(
        key: String,
        from: Instant,
        to: Instant,
    ): MetricsQuery = MetricsQuery(DomainValueFactory.promptKey(key), from, to)
}
