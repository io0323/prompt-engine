package promptengine.domain.fragment

import promptengine.domain.event.DomainEvent

/**
 * Fragment Aggregateが発行するDomain Eventの基底型（設計書§14、ADR-0033）。
 * `eventType` はクラス名（過去形、設計書§4.6）、`aggregateType` は固定で "Fragment"。
 */
sealed class FragmentDomainEvent : DomainEvent {
    override val eventType: String get() = this::class.simpleName ?: "Unknown"
    override val aggregateType: String get() = "Fragment"
}
