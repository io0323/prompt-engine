package promptengine.application.view

import promptengine.application.query.AuditLogsHandler
import promptengine.application.query.DependenciesHandler
import promptengine.application.query.DependenciesQuery
import promptengine.application.query.DiffHandler
import promptengine.application.query.DiffQuery
import promptengine.application.query.GetVersionHandler
import promptengine.application.query.GetVersionQuery
import promptengine.application.query.MetricsHandler
import promptengine.application.query.MetricsQuery
import promptengine.application.query.SearchPromptsHandler
import promptengine.application.query.SearchPromptsQuery
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditQuery
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.metrics.MetricsSummary
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * dependency/metrics/audit-logs系のView変換（[PromptViews.kt][promptengine.application.view]のKDoc参照）。
 */
data class DependencyEdgeView(
    val fromKey: String,
    val fromVersion: String,
    val toKind: String,
    val toKey: String,
    val toVersion: String?,
)

/** `DependencyEdge`を[DependencyEdgeView]へ変換する。 */
fun DependencyEdge.toView(): DependencyEdgeView =
    DependencyEdgeView(fromKey.value, fromVersion.toString(), toKind.name, toKey, toVersion)

/** `MetricsSummary`（`GET /metrics/prompts/{namespace}/{name}`）のView。 */
data class MetricsSummaryView(
    val promptKey: String,
    val from: Instant,
    val to: Instant,
    val executionCount: Long,
    val successCount: Long,
    val successRate: Double,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCost: BigDecimal,
    val averageLatencyMs: Long,
)

/** `MetricsSummary`を[MetricsSummaryView]へ変換する。 */
fun MetricsSummary.toView(): MetricsSummaryView =
    MetricsSummaryView(
        promptKey = promptKey.value,
        from = from,
        to = to,
        executionCount = executionCount,
        successCount = successCount,
        successRate = successRate,
        totalInputTokens = totalInputTokens.value,
        totalOutputTokens = totalOutputTokens.value,
        totalCost = totalCost.value,
        averageLatencyMs = averageLatency.value,
    )

/** `AuditLogEntry`（`GET /audit-logs`結果の1行）のView。 */
data class AuditLogEntryView(
    val auditId: UUID,
    val aggregateType: String,
    val aggregateId: String,
    val action: String,
    val actor: String,
    val payload: String,
    val traceId: String,
    val occurredAt: Instant,
)

/** `AuditLogEntry`を[AuditLogEntryView]へ変換する。 */
fun AuditLogEntry.toView(): AuditLogEntryView =
    AuditLogEntryView(auditId, aggregateType, aggregateId, action, actor, payload, traceId, occurredAt)

/**
 * Query系ハンドラのうち`handle()`の戻り値型自体、または引数の型自体がdomain型
 * （`PromptVersion`・`PromptVersionDiff`・`List<DependencyEdge>`・`MetricsSummary`・
 * `Page<T>`・`AuditQuery`）であるものは、`prompt-engine-interface`が`.handle(query)`を
 * 直接呼ぶだけでは済まない。`prompt-engine-interface`の`implementation(project(":modules:
 * prompt-engine-application"))`はGradleの`implementation`可視性であり、
 * `prompt-engine-application`自身が`prompt-engine-domain`を`implementation`（`api`ではない）
 * で依存しているため、domain型は`prompt-engine-interface`のコンパイルクラスパス上に
 * そもそも存在しない（明示的なimportの有無に関わらず、推論された中間型としても
 * 解決できない、コンパイルエラー）。ArchUnitルールの検証を待つまでもなく、
 * Gradleのモジュール依存構成自体がこれを強制する。
 *
 * `AuditQuery`のみ引数自体がdomain型であるため、`handleView`はQuery構築もここで行う
 * （[PromptCommandFactory]等へ切り出さず、プリミティブ引数を直接受ける）。他は戻り値のみが
 * domain型なので、application層の型（各`*Query`）を引数に取れる。[TimeRange]・[Paging]は
 * detekt LongParameterList閾値（拡張関数はレシーバも計上されるため実質5）対策のグルーピング。
 *
 * [GetVersionHandler.handleView]は`GET /prompts/{namespace}/{name}/versions/{version}`。
 * [GetVersionHandler.handle]の結果をViewへ変換する。
 */
fun GetVersionHandler.handleView(query: GetVersionQuery): PromptVersionView = handle(query).toView()

/** `GET /prompts/{namespace}/{name}/diff`。[DiffHandler.handle]の結果をViewへ変換する。 */
fun DiffHandler.handleView(query: DiffQuery): PromptVersionDiffView = handle(query).toView()

/** `GET /prompts/{namespace}/{name}/dependencies`。[DependenciesHandler.handle]の結果をViewへ変換する。 */
fun DependenciesHandler.handleView(query: DependenciesQuery): List<DependencyEdgeView> =
    handle(query).map { it.toView() }

/** `GET /metrics/prompts/{namespace}/{name}`。[MetricsHandler.handle]の結果をViewへ変換する。 */
fun MetricsHandler.handleView(query: MetricsQuery): MetricsSummaryView = handle(query).toView()

/** `GET /prompts`（検索）。[SearchPromptsHandler.handle]の結果をViewへ変換する。 */
fun SearchPromptsHandler.handleView(query: SearchPromptsQuery): PageView<PromptSummaryView> =
    handle(query).toView { it.toView() }

/** `GET /audit-logs`。プリミティブ引数から`AuditQuery`を組み立て、[AuditLogsHandler.handle]の結果をViewへ変換する。 */
fun AuditLogsHandler.handleView(
    aggregateId: String?,
    actor: String?,
    range: TimeRange,
    paging: Paging,
): PageView<AuditLogEntryView> =
    handle(AuditQuery(aggregateId, actor, range.from, range.to, paging.page, paging.size)).toView { it.toView() }
