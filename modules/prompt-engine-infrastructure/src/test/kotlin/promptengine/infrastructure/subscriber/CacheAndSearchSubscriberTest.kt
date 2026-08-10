package promptengine.infrastructure.subscriber

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventTopic
import promptengine.domain.prompt.PromptKey
import promptengine.infrastructure.cache.InMemoryPromptCacheInvalidator
import promptengine.infrastructure.search.InMemoryPromptSearchIndexer

/** Cache Invalidator / Search Indexer の購読（ADR-0026決定6）。 */
class CacheAndSearchSubscriberTest {
    @Test
    fun `CacheInvalidatorはpe_promptトピックのみを購読する`() {
        CacheInvalidationSubscriber(InMemoryPromptCacheInvalidator()).topics shouldBe setOf(EventTopic.PE_PROMPT)
    }

    @Test
    fun `PromptPublishedでinvalidateByPromptが呼ばれる`() {
        val invalidator = InMemoryPromptCacheInvalidator()

        CacheInvalidationSubscriber(invalidator).handle(
            envelope(eventType = "PromptPublished", aggregateId = "support/faq"),
        )

        invalidator.snapshot() shouldContainExactly listOf(PromptKey("support/faq"))
    }

    @Test
    fun `配信内容が切り替わる他イベントでも無効化する`() {
        val invalidator = InMemoryPromptCacheInvalidator()
        val subscriber = CacheInvalidationSubscriber(invalidator)

        listOf("PromptRolledBack", "PromptArchived", "PromptDiscarded").forEach { eventType ->
            subscriber.handle(envelope(eventType = eventType, aggregateId = "support/faq"))
        }

        invalidator.snapshot().size shouldBe 3
    }

    @Test
    fun `配信内容に影響しないイベントでは無効化しない`() {
        val invalidator = InMemoryPromptCacheInvalidator()

        CacheInvalidationSubscriber(invalidator).handle(envelope(eventType = "PromptValidated"))

        invalidator.snapshot() shouldBe emptyList()
    }

    /**
     * `pe.prompt`には`domain_events`由来のイベントも流れ、そちらの`aggregateId`は
     * `prompts.prompt_id`（UUID文字列）でPromptKeyとして解釈できない（ADR-0025決定7）。
     * 無効化対象を特定できないため何もしない（例外にしてDLQを汚さない）。
     */
    @Test
    fun `aggregateIdがPromptKeyとして解釈できない場合は何もしない`() {
        val invalidator = InMemoryPromptCacheInvalidator()

        CacheInvalidationSubscriber(invalidator).handle(
            envelope(eventType = "PromptPublished", aggregateId = "not a valid prompt key!!"),
        )

        invalidator.snapshot() shouldBe emptyList()
    }

    @Test
    fun `SearchIndexerはpe_promptトピックのみを購読する`() {
        SearchIndexSubscriber(InMemoryPromptSearchIndexer()).topics shouldBe setOf(EventTopic.PE_PROMPT)
    }

    @Test
    fun `内容や状態が変化するイベントでインデックスを張り直す`() {
        val indexer = InMemoryPromptSearchIndexer()
        val subscriber = SearchIndexSubscriber(indexer)

        subscriber.handle(envelope(eventType = "PromptCreated", aggregateId = "support/faq"))
        subscriber.handle(envelope(eventType = "PromptPublished", aggregateId = "support/faq"))

        indexer.snapshot() shouldBe setOf(PromptKey("support/faq"))
    }

    @Test
    fun `ライフサイクル終端のイベントでインデックスから落とす`() {
        val indexer = InMemoryPromptSearchIndexer()
        val subscriber = SearchIndexSubscriber(indexer)
        subscriber.handle(envelope(eventType = "PromptCreated", aggregateId = "support/faq"))

        subscriber.handle(envelope(eventType = "PromptArchived", aggregateId = "support/faq"))

        indexer.snapshot() shouldBe emptySet()
    }

    @Test
    fun `対象外のイベント種別では何もしない`() {
        val indexer = InMemoryPromptSearchIndexer()

        SearchIndexSubscriber(indexer).handle(envelope(eventType = "PromptRendered", aggregateId = "support/faq"))

        indexer.snapshot() shouldBe emptySet()
    }

    @Test
    fun `SearchIndexerもaggregateIdを解釈できない場合は何もしない`() {
        val indexer = InMemoryPromptSearchIndexer()

        SearchIndexSubscriber(indexer).handle(envelope(eventType = "PromptCreated", aggregateId = "bad key!!"))

        indexer.snapshot() shouldBe emptySet()
    }
}
