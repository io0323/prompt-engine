package promptengine.application.command

import promptengine.domain.prompt.PromptAlias
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptKey

class InMemoryPromptAliasRepository : PromptAliasRepository {
    private val store = mutableMapOf<Pair<PromptKey, String>, PromptAlias>()

    override fun find(
        promptKey: PromptKey,
        alias: String,
    ): PromptAlias? = store[promptKey to alias]

    override fun findAll(promptKey: PromptKey): List<PromptAlias> = store.values.filter { it.promptKey == promptKey }

    override fun upsert(alias: PromptAlias) {
        store[alias.promptKey to alias.alias] = alias
    }
}
