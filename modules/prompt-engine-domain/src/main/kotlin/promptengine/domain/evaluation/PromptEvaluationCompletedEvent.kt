package promptengine.domain.evaluation

import promptengine.domain.event.DomainEvent
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * 非同期評価の完了（設計書§14 `PromptEvaluationCompleted`、発火元「Evaluation Engine」、
 * Topic `pe.evaluation`）。
 *
 * [promptengine.domain.pipeline.PromptExecutedEvent]と同じく、Prompt Aggregate自身の状態遷移
 * イベント（[promptengine.domain.prompt.PromptDomainEvent]）ではないため`aggregateType`は
 * `"Prompt"`固定としつつ独立した[DomainEvent]実装とする。Topicルーティングは
 * [promptengine.domain.event.EventTopicResolver]がADR-0025決定7で既に予約済み。
 *
 * [payload]は指標名とスコアのみを持ち、生のprompt/response内容・Secretを含まない。
 */
data class PromptEvaluationCompletedEvent(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : DomainEvent {
    override val eventType: String = "PromptEvaluationCompleted"
    override val aggregateType: String = "Prompt"

    /**
     * [sourceEventId]は評価の元になった`PromptExecuted`の`event_id`。購読側が評価結果と
     * 実行ログ（`execution_logs.event_id`）を突き合わせられるようにする。
     */
    data class Payload(
        val promptKey: String,
        val semVer: String,
        val sourceEventId: UUID,
        val metrics: List<Metric>,
    )

    data class Metric(
        val metricType: String,
        val score: BigDecimal,
        val method: String,
    )
}
