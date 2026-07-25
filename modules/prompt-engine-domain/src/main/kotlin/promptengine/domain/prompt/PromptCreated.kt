package promptengine.domain.prompt

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/** Prompt新規作成（設計書§14）。 */
data class PromptCreated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : PromptDomainEvent() {
    data class Payload(val promptKey: String, val semVer: SemVer)
}
