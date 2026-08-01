package promptengine.domain.prompt

/**
 * [PromptMetadata]の永続化インターフェース（ADR-0020）。実装は`prompt-engine-infrastructure`。
 */
interface PromptMetadataRepository {
    fun find(key: PromptKey): PromptMetadata?

    /** [metadata]を作成または更新する（`key`が既存なら更新、無ければ作成）。 */
    fun upsert(metadata: PromptMetadata)
}
