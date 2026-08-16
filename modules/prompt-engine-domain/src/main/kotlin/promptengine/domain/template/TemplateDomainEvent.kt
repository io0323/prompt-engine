package promptengine.domain.template

import promptengine.domain.event.DomainEvent

/**
 * Template Aggregateが発行するDomain Eventの基底型（設計書§14、ADR-0033）。
 * `eventType` はクラス名（過去形、設計書§4.6）、`aggregateType` は固定で "Template"。
 */
sealed class TemplateDomainEvent : DomainEvent {
    override val eventType: String get() = this::class.simpleName ?: "Unknown"
    override val aggregateType: String get() = "Template"
}
