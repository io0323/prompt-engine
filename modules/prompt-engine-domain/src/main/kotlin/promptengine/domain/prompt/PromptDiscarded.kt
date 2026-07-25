package promptengine.domain.prompt

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * Draftの破棄（Draft→Archived）。ADR-0004で追加。
 * ReviewCaseを経由しないPrompt Aggregate自身の操作のため、ここでイベントを発行する。
 */
data class PromptDiscarded(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : PromptDomainEvent() {
    data class Payload(val promptKey: String, val semVer: SemVer)
}
