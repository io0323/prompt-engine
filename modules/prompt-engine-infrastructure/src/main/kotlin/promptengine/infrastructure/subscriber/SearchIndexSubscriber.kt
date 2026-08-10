package promptengine.infrastructure.subscriber

import promptengine.domain.event.EventEnvelope
import promptengine.domain.event.EventSubscriber
import promptengine.domain.event.EventTopic
import promptengine.domain.prompt.PromptKey
import promptengine.domain.search.PromptSearchIndexer

/**
 * `pe.prompt`を購読して検索インデックスを更新する（設計書§2.14 Search Indexer、
 * ADR-0026決定6）。
 *
 * `docs/prompts/p10b.md`が「Search Indexer は M1 では簡易実装でよい」と明示しているため、
 * 本クラスは購読とポート呼び出しの配線のみを持ち、インデックスの実体は
 * [PromptSearchIndexer]の実装（M1は[promptengine.infrastructure.search.InMemoryPromptSearchIndexer]）に委ねる。
 *
 * ライフサイクルが終端に達したイベント（Archived / Discarded）は[PromptSearchIndexer.remove]、
 * それ以外の内容・状態変化は[PromptSearchIndexer.index]へ振り分ける。
 */
class SearchIndexSubscriber(
    private val searchIndexer: PromptSearchIndexer,
) : EventSubscriber {
    override val name: String = SUBSCRIBER_NAME

    override val topics: Set<EventTopic> = setOf(EventTopic.PE_PROMPT)

    override fun handle(envelope: EventEnvelope) {
        val key = runCatching { PromptKey(envelope.aggregateId) }.getOrNull() ?: return
        when (envelope.eventType) {
            in REMOVING_EVENT_TYPES -> searchIndexer.remove(key)
            in INDEXING_EVENT_TYPES -> searchIndexer.index(key)
            else -> Unit
        }
    }

    companion object {
        /** Brokerのconsumer group IDおよび`dead_letter_queue.subscriber_name`。 */
        const val SUBSCRIBER_NAME = "pe-search-indexer"

        /** インデックスから落とすイベント（ライフサイクル終端）。 */
        val REMOVING_EVENT_TYPES = setOf("PromptArchived", "PromptDiscarded")

        /** インデックスを張り直すイベント（内容・状態の変化）。 */
        val INDEXING_EVENT_TYPES =
            setOf("PromptCreated", "PromptVersionCreated", "PromptUpdated", "PromptPublished", "PromptRolledBack")
    }
}
