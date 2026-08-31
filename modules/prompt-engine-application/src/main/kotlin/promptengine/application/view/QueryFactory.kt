package promptengine.application.view

import promptengine.application.query.AssetImpactQuery
import promptengine.application.query.DependenciesQuery
import promptengine.application.query.DependencyDirection
import promptengine.application.query.MetricsQuery
import promptengine.domain.dependency.DependencyKind
import java.time.Instant

/**
 * `DependencyController`・`MetricsController`・`AssetImpactController`が使うQueryを構築する（P9c）。
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

    /**
     * `GET /templates/{namespace}/{name}/impact` および
     * `GET /fragments/{namespace}/{name}/impact`（UC-06）用の[AssetImpactQuery]を構築する。
     *
     * [kindName]は`"TEMPLATE"`または`"FRAGMENT"`（大小無視）。それ以外は[IllegalArgumentException]。
     * `namespace`/`name`の正当性検証は[DependencyKind]の列挙評価のみで行い、
     * Template/FragmentのKeyバリデーション（`[a-z0-9-]+(/[a-z0-9-]+)+`）は
     * GlobalExceptionHandlerに委ねる。
     */
    fun assetImpactQuery(
        kindName: String,
        namespace: String,
        name: String,
    ): AssetImpactQuery {
        val kind =
            runCatching { DependencyKind.valueOf(kindName.uppercase()) }
                .getOrElse { throw IllegalArgumentException("不正なkind: $kindName。TEMPLATE または FRAGMENT を指定してください。") }
        return AssetImpactQuery(kind, "$namespace/$name")
    }
}
