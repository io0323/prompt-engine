package promptengine.domain.prompt

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * 著者自身によるレビュー取り下げ（InReview→Draft）。ADR-0004で追加。
 * reject（レビュー担当による差し戻し）とは異なり、ReviewCaseを経由しない
 * Prompt Aggregate自身の操作のため、ここでイベントを発行する。
 */
data class PromptWithdrawn(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : PromptDomainEvent() {
    data class Payload(val promptKey: String, val semVer: SemVer)
}
