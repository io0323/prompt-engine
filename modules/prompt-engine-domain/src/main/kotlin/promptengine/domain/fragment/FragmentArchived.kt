package promptengine.domain.fragment

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/** Fragment Version廃止（設計書§14、ADR-0033）。 */
data class FragmentArchived(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : FragmentDomainEvent() {
    data class Payload(val fragmentKey: String, val semVer: SemVer)
}
