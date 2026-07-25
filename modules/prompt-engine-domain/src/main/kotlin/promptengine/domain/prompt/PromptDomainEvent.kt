package promptengine.domain.prompt

import promptengine.domain.event.DomainEvent

/**
 * Prompt Aggregateが発行するDomain Eventの基底型。
 * `eventType` はクラス名（過去形、設計書§4.6）、`aggregateType` は固定で "Prompt"。
 */
sealed class PromptDomainEvent : DomainEvent {
    override val eventType: String get() = this::class.simpleName ?: "Unknown"
    override val aggregateType: String get() = "Prompt"
}
