package promptengine.domain.template

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/** Template新規作成（設計書§14、ADR-0033）。 */
data class TemplateCreated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : TemplateDomainEvent() {
    data class Payload(val templateKey: String, val semVer: SemVer)
}
