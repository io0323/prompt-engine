package promptengine.domain.governance

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * 差戻し（InReview→Draft、設計書§14）。発火元は`ReviewCase`（ADR-0004）。
 * [Payload.comment] は設計書§13.1「差戻し（comment必須）」に対応する必須の却下理由。
 */
data class PromptRejected(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : ReviewCaseDomainEvent() {
    data class Payload(val promptKey: String, val semVer: SemVer, val comment: String)
}
