package promptengine.application.command

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.PromptDomainEvent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptMetadataRepository
import promptengine.domain.prompt.PromptUpdated

/** `PromptUpdated`が設計書§14の封筒8項目を満たすことを検証する（P9bレビュー要件）。 */
class UpdatePromptMetadataHandlerTest {
    private class RecordingPromptMetadataRepository : PromptMetadataRepository {
        var lastMetadata: PromptMetadata? = null
        val recordedEvents = mutableListOf<PromptDomainEvent>()

        override fun find(key: PromptKey): PromptMetadata? = lastMetadata

        override fun upsert(
            metadata: PromptMetadata,
            events: List<PromptDomainEvent>,
        ) {
            lastMetadata = metadata
            recordedEvents += events
        }
    }

    @Test
    fun `PromptUpdatedの封筒8項目が全て埋まっている`() {
        val repository = RecordingPromptMetadataRepository()
        val handler = UpdatePromptMetadataHandler(repository, PassthroughIdempotentCommandExecutor())
        val key = PromptKey("team/greeting")

        handler.handle(UpdatePromptMetadataCommand(key, name = "挨拶", actor = "tester", traceId = "trace-7"))

        repository.recordedEvents.size shouldBe 1
        val event = repository.recordedEvents.single() as PromptUpdated
        event.eventId shouldNotBe null
        event.eventType shouldBe "PromptUpdated"
        event.occurredAt shouldNotBe null
        event.aggregateType shouldBe "Prompt"
        event.aggregateId shouldBe key.value
        event.actor shouldBe "tester"
        event.traceId shouldBe "trace-7"
        event.payload shouldBe PromptUpdated.Payload(key.value)
    }
}
