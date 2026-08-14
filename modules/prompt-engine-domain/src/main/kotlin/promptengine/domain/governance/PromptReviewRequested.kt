package promptengine.domain.governance

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/** Draft→InReviewのレビュー依頼（設計書§14）。発火元は`ReviewCase`（ADR-0004）。 */
data class PromptReviewRequested(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : ReviewCaseDomainEvent() {
    data class Payload(val promptKey: String, val semVer: SemVer)
}
