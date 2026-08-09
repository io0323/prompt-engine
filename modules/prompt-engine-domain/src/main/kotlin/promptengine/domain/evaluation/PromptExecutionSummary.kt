package promptengine.domain.evaluation

import promptengine.domain.execution.Usage
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * `PromptExecuted`（設計書§14）1件が運ぶ、評価・実行ログ記録に必要な実行1回分の要約
 * （ADR-0026決定3）。
 *
 * [promptengine.domain.pipeline.PromptExecutedEvent.Payload]をBroker経由で受け取った購読側が
 * 逆シリアライズして組み立てる、購読側共通の入力型。生のprompt/response内容は一切含まない
 * （[promptengine.domain.audit.AuditRecord]と同じ設計思想）。
 *
 * [costPerToken]は発行時点の[promptengine.domain.optimization.ModelProfile.costPerToken]
 * （`PipelineRequest.modelProfile`由来）をイベントへ写したもの。購読側が後から
 * ModelProfileを引き直すと、単価改定後に過去の実行を再評価した際に当時と違うコストが
 * 出てしまうため、実行時点の単価をイベント自身に載せる。
 *
 * [latency]はStage 9（Execution）の実測時間（`PipelineContext.stageDurationsMs["Execution"]`、
 * 設計書§2.12「Latency | Execution Stage実測」）。
 */
data class PromptExecutionSummary(
    val eventId: UUID,
    val promptKey: String,
    val semVer: SemVer,
    val latency: LatencyMs,
    val usage: Usage,
    val costPerToken: Cost,
    val status: ExecutionStatus,
    val retryCount: Int,
    val callerSystem: String,
    val traceId: String,
    val occurredAt: Instant,
) {
    init {
        require(promptKey.isNotBlank()) { "promptKey must not be blank" }
        require(retryCount >= 0) { "retryCount must not be negative: $retryCount" }
        require(callerSystem.isNotBlank()) { "callerSystem must not be blank" }
    }

    /** 入力・出力トークンの合計。[EvaluationRule]実装とコスト算出の共通の素地。 */
    val totalTokens: Int get() = usage.inputTokens.value + usage.outputTokens.value
}
