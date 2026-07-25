package promptengine.domain.prompt

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/** 過去Versionへの再Publish（障害復旧、設計書§14・§2.13）。 */
data class PromptRolledBack(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : PromptDomainEvent() {
    data class Payload(val promptKey: String, val fromSemVer: SemVer, val toSemVer: SemVer)
}
