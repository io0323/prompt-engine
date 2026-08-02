package promptengine.domain.pipeline

import promptengine.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Pipeline Full-execution完了（設計書§14 `PromptExecuted`、発火元「Pipeline Orchestrator」、
 * 購読先「Evaluation Engine, Monitoring, Audit」）。
 *
 * `Prompt`/`PromptVersion` Aggregate自身が発行する[promptengine.domain.prompt.PromptDomainEvent]系
 * とは異なり、発火元がPipeline Orchestratorであるため`domain.pipeline`に置く。
 * [payload]は秘密情報・生のprompt/response内容を含まない構造的な要約のみ
 * （[AuditRecord][promptengine.domain.audit.AuditRecord]と同じ設計思想）。
 */
data class PromptExecutedEvent(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : DomainEvent {
    override val eventType: String = "PromptExecuted"
    override val aggregateType: String = "Prompt"

    data class Payload(
        val promptKey: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val retryCount: Int,
    )
}
