package promptengine.application.command

import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptDomainEvent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository

/** テスト用フェイク。`row_version`楽観ロック等は検証しない、単純なMapベースの永続化。 */
class InMemoryPromptRepository : PromptRepository {
    private val store = mutableMapOf<PromptKey, Prompt>()
    val savedEvents = mutableListOf<PromptDomainEvent>()

    fun seed(prompt: Prompt) {
        store[prompt.key] = prompt
    }

    override fun findByKey(key: PromptKey): Prompt? = store[key]

    override fun save(
        prompt: Prompt,
        events: List<PromptDomainEvent>,
    ): Prompt {
        store[prompt.key] = prompt
        savedEvents += events
        return prompt
    }
}
