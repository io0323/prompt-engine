package promptengine.domain.template

import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * Template Version配信切替（設計書§14、ADR-0033）。
 * `CacheInvalidationSubscriber`が`payload.semVer`とこのTemplateへの逆依存
 * （`DependencyRepository`、`toVersion`のSemVer範囲一致）から、無効化すべきPromptを特定する。
 */
data class TemplatePublished(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : TemplateDomainEvent() {
    data class Payload(val templateKey: String, val semVer: SemVer)
}
