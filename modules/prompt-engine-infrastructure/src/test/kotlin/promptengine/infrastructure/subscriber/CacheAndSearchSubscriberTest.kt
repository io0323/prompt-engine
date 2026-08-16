package promptengine.infrastructure.subscriber

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.event.EventTopic
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.search.InMemoryPromptSearchIndexer

/** Cache Invalidator / Search Indexer の購読（ADR-0026決定6、Template/Fragment対応はADR-0033）。 */
class CacheAndSearchSubscriberTest {
    private val codec = CacheInvalidationPayloadCodec(testObjectMapper)

    private fun subscriberWith(
        cache: RecordingPromptCache = RecordingPromptCache(),
        dependencyRepository: FakeDependencyRepository = FakeDependencyRepository(),
    ): CacheInvalidationSubscriber = CacheInvalidationSubscriber(cache, dependencyRepository, codec)

    @Test
    fun `CacheInvalidatorはpe_promptトピックのみを購読する`() {
        subscriberWith().topics shouldBe setOf(EventTopic.PE_PROMPT)
    }

    @Test
    fun `PromptPublishedはpayloadのpromptKeyでinvalidateByPromptを呼ぶ`() {
        val cache = RecordingPromptCache()

        subscriberWith(cache).handle(
            envelope(
                eventType = "PromptPublished",
                aggregateId = UUID_LOOKING_AGGREGATE_ID,
                payload = promptKeyedPayload(),
            ),
        )

        cache.invalidated shouldContainExactly listOf(PromptKey("support/faq"))
    }

    @Test
    fun `配信内容が切り替わる他イベントでも無効化する`() {
        val cache = RecordingPromptCache()
        val subscriber = subscriberWith(cache)

        listOf("PromptRolledBack", "PromptArchived", "PromptDiscarded").forEach { eventType ->
            subscriber.handle(envelope(eventType = eventType, payload = promptKeyedPayload()))
        }

        cache.invalidated.size shouldBe 3
    }

    @Test
    fun `配信内容に影響しないイベントでは無効化しない`() {
        val cache = RecordingPromptCache()

        subscriberWith(cache).handle(envelope(eventType = "PromptValidated"))

        cache.invalidated shouldBe emptyList()
    }

    /**
     * P10b時点の不具合の回帰テスト: `aggregateId`は`domain_events`由来イベントでは
     * DBサロゲートキー（UUID）であり業務キーではない（`DomainEventOutboxSource`参照）。
     * 本コーデックは`aggregateId`を一切参照しないため、UUID風の`aggregateId`が来ても
     * `payload`のpromptKeyから正しく無効化できることを確認する（キーが一致することの
     * 確認ではなく、実際に無効化が起きることそのものを確認する）。
     */
    @Test
    fun `aggregateIdがUUIDでもpayloadのpromptKeyから正しく無効化できる`() {
        val cache = RecordingPromptCache()

        subscriberWith(cache).handle(
            envelope(
                eventType = "PromptPublished",
                aggregateId = UUID_LOOKING_AGGREGATE_ID,
                payload = promptKeyedPayload(key = "team/other"),
            ),
        )

        cache.invalidated shouldContainExactly listOf(PromptKey("team/other"))
    }

    @Test
    fun `promptKeyを欠くpayloadでは何もしない`() {
        val cache = RecordingPromptCache()

        subscriberWith(cache).handle(envelope(eventType = "PromptPublished", payload = "{}"))

        cache.invalidated shouldBe emptyList()
    }

    @Test
    fun `TemplatePublishedはSemVer範囲が一致する逆依存Promptだけを無効化する`() {
        val cache = RecordingPromptCache()
        val matching = PromptKey("team/uses-v2")
        val nonMatching = PromptKey("team/pinned-v1")
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(
                    DependencyEdge(matching, SemVer(1, 0, 0), DependencyKind.TEMPLATE, "team/base", "^2"),
                    DependencyEdge(nonMatching, SemVer(1, 0, 0), DependencyKind.TEMPLATE, "team/base", "1.0.0"),
                ),
            )

        subscriberWith(cache, dependencyRepository).handle(
            envelope(
                eventType = "TemplatePublished",
                aggregateId = UUID_LOOKING_AGGREGATE_ID,
                payload = promptKeyedPayload(field = "templateKey", key = "team/base", major = 2, minor = 0, patch = 0),
            ),
        )

        // 「無効化が1件でも起きたか」ではなく「一致するPromptだけが無効化され、
        // 一致しないPromptは無効化されていないか」を内容で確認する。
        cache.invalidated shouldContainExactly listOf(matching)
    }

    @Test
    fun `FragmentArchivedもSemVer範囲一致で無効化する`() {
        val cache = RecordingPromptCache()
        val matching = PromptKey("team/uses-fragment")
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(
                    DependencyEdge(matching, SemVer(1, 0, 0), DependencyKind.FRAGMENT, "team/notice", "1.2.0"),
                ),
            )

        subscriberWith(cache, dependencyRepository).handle(
            envelope(
                eventType = "FragmentArchived",
                aggregateId = UUID_LOOKING_AGGREGATE_ID,
                payload =
                    promptKeyedPayload(field = "fragmentKey", key = "team/notice", major = 1, minor = 2, patch = 0),
            ),
        )

        cache.invalidated shouldContainExactly listOf(matching)
    }

    @Test
    fun `複数Promptが逆依存する場合は一致するもの全てを重複なく無効化する`() {
        val cache = RecordingPromptCache()
        val first = PromptKey("team/first")
        val second = PromptKey("team/second")
        val dependencyRepository =
            FakeDependencyRepository(
                listOf(
                    DependencyEdge(first, SemVer(1, 0, 0), DependencyKind.TEMPLATE, "team/base", "^1"),
                    DependencyEdge(second, SemVer(1, 0, 0), DependencyKind.TEMPLATE, "team/base", null),
                ),
            )

        subscriberWith(cache, dependencyRepository).handle(
            envelope(
                eventType = "TemplatePublished",
                payload = promptKeyedPayload(field = "templateKey", key = "team/base", major = 1, minor = 1, patch = 0),
            ),
        )

        cache.invalidated shouldContainExactlyInAnyOrder listOf(first, second)
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

    private companion object {
        /** `domain_events`由来イベントの実際のaggregateId形状（DBサロゲートキー）を模す。 */
        const val UUID_LOOKING_AGGREGATE_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
