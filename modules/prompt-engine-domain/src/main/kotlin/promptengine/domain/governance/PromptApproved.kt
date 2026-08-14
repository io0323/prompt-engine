package promptengine.domain.governance

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * 承認確定（必要承認数充足、InReview→Approved、設計書§14）。発火元は`ReviewCase`（ADR-0004）。
 * 個々の承認者は`actor`（封筒）が表す。複数承認者のうち閾値を満たした最後の1件でのみ発行される
 * （[promptengine.domain.governance.ReviewCase.approve]参照）。
 */
data class PromptApproved(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : ReviewCaseDomainEvent() {
    data class Payload(val promptKey: String, val semVer: SemVer)
}
