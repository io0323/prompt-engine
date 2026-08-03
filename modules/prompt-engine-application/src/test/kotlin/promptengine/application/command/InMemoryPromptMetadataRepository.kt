package promptengine.application.command

import promptengine.domain.prompt.PromptDomainEvent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptMetadataRepository

class InMemoryPromptMetadataRepository : PromptMetadataRepository {
    private val store = mutableMapOf<PromptKey, PromptMetadata>()
    val recordedEvents = mutableListOf<PromptDomainEvent>()

    override fun find(key: PromptKey): PromptMetadata? = store[key]

    override fun upsert(
        metadata: PromptMetadata,
        events: List<PromptDomainEvent>,
    ) {
        store[metadata.key] = metadata
        recordedEvents += events
    }
}
