package promptengine.domain.prompt

/**
 * [PromptAlias]の永続化インターフェース。実装は`prompt-engine-infrastructure`。
 */
interface PromptAliasRepository {
    fun find(
        promptKey: PromptKey,
        alias: String,
    ): PromptAlias?

    fun findAll(promptKey: PromptKey): List<PromptAlias>

    /** [alias]を作成または更新する（同一`promptKey`+`alias`が既存なら参照先Versionを更新）。 */
    fun upsert(alias: PromptAlias)
}
