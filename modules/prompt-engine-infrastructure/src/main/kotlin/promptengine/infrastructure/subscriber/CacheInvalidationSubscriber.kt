package promptengine.infrastructure.subscriber

import promptengine.domain.cache.PromptCacheInvalidator
import promptengine.domain.event.EventEnvelope
import promptengine.domain.event.EventSubscriber
import promptengine.domain.event.EventTopic
import promptengine.domain.prompt.PromptKey

/**
 * Promptの配信内容が変化するイベントを購読し、そのPromptのキャッシュを無効化する
 * （設計書§14 `CacheInvalidated`、ADR-0026決定6）。
 *
 * `PromptPublished`（配信Version切替）を主対象とし、配信内容が実質的に切り替わる
 * `PromptRolledBack` / `PromptArchived` / `PromptDiscarded`も同じ扱いにする
 * （`docs/prompts/p10b.md`「Cache Invalidator（PromptPublished 等で invalidateByPrompt）」の
 * 「等」の解釈。設計書§14に無い判断ではなく、いずれも§14の`pe.prompt`トピックに
 * 定義済みのイベント）。
 *
 * `aggregateId`はビジネスキー（`PromptKey.value`）。`pe.prompt`には`domain_events`由来の
 * イベントも流れ、そちらの`aggregateId`は`prompts.prompt_id`（UUID文字列）である
 * （ADR-0025決定7・`DomainEventOutboxSource`）。[PromptKey]として解釈できない`aggregateId`は
 * 無効化対象を特定できないため何もしない（例外にしてDLQを汚さない）。
 */
class CacheInvalidationSubscriber(
    private val cacheInvalidator: PromptCacheInvalidator,
) : EventSubscriber {
    override val name: String = SUBSCRIBER_NAME

    override val topics: Set<EventTopic> = setOf(EventTopic.PE_PROMPT)

    override fun handle(envelope: EventEnvelope) {
        if (envelope.eventType !in INVALIDATING_EVENT_TYPES) return
        val key = runCatching { PromptKey(envelope.aggregateId) }.getOrNull() ?: return
        cacheInvalidator.invalidateByPrompt(key)
    }

    companion object {
        /** Brokerのconsumer group IDおよび`dead_letter_queue.subscriber_name`。 */
        const val SUBSCRIBER_NAME = "pe-cache-invalidator"

        /** 配信されるPromptの内容が実質的に切り替わる、設計書§14の`pe.prompt`イベント。 */
        val INVALIDATING_EVENT_TYPES =
            setOf("PromptPublished", "PromptRolledBack", "PromptArchived", "PromptDiscarded")
    }
}
