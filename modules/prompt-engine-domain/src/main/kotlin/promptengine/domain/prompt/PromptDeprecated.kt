package promptengine.domain.prompt

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * Version廃止（設計書§14）。`reason` が [DeprecationReason.SUPERSEDED] の場合は
 * publish/rollbackによる自動遷移、[DeprecationReason.MANUAL] の場合は手動deprecate（ADR-0005）。
 */
data class PromptDeprecated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : PromptDomainEvent() {
    data class Payload(
        val promptKey: String,
        val semVer: SemVer,
        val recommendedReplacement: VersionRef?,
        val reason: DeprecationReason,
    )
}
