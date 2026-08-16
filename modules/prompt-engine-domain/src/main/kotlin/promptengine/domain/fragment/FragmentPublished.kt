package promptengine.domain.fragment

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * Fragment Version配信切替（設計書§14、ADR-0033）。
 * `CacheInvalidationSubscriber`が`payload.semVer`とこのFragmentへの逆依存
 * （`DependencyRepository`、`toVersion`のSemVer範囲一致）から、無効化すべきPromptを特定する。
 */
data class FragmentPublished(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : FragmentDomainEvent() {
    data class Payload(val fragmentKey: String, val semVer: SemVer)
}
