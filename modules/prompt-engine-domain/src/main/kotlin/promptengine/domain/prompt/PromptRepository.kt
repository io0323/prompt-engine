package promptengine.domain.prompt

/**
 * Prompt Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P2）で行う。
 */
interface PromptRepository {
    fun findByKey(key: PromptKey): Prompt?

    fun save(prompt: Prompt): Prompt
}
