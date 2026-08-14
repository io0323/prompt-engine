package promptengine.domain.governance

import promptengine.domain.event.DomainEvent

/**
 * `ReviewCase` Aggregateが発行するDomain Eventの基底型（設計書§4.1 Governanceコンテキスト、
 * ADR-0004）。`eventType` はクラス名（過去形、設計書§4.6）、`aggregateType` は固定で
 * "ReviewCase"、`aggregateId` は`reviewId`の文字列表現。
 */
sealed class ReviewCaseDomainEvent : DomainEvent {
    override val eventType: String get() = this::class.simpleName ?: "Unknown"
    override val aggregateType: String get() = "ReviewCase"
}
