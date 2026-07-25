package promptengine.domain.event

import java.time.Instant
import java.util.UUID

/**
 * 全Bounded Context共通のDomain Event封筒（設計書§14）。
 */
interface DomainEvent {
    val eventId: UUID
    val eventType: String
    val occurredAt: Instant
    val aggregateType: String
    val aggregateId: String
    val actor: String
    val traceId: String
    val payload: Any
}
