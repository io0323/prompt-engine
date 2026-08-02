package promptengine.domain.prompt

/**
 * [PromptMetadata]の永続化インターフェース（ADR-0020）。実装は`prompt-engine-infrastructure`。
 */
interface PromptMetadataRepository {
    /** [key]に対応するメタデータを返す。`Prompt` Aggregateまたはメタデータが未登録なら`null`。 */
    fun find(key: PromptKey): PromptMetadata?

    /**
     * [metadata]を作成または更新する。[events]（`PromptUpdated`等）は`metadata`の書き込みと
     * 同一トランザクションで`domain_events`/`outbox`へ追記する（P9b、設計書§14）。
     *
     * `key`に対応する`Prompt` Aggregateが既に永続化済み（`Prompt.create`＋
     * `PromptRepository.save`が先に実行済み）であることを前提とする。実装
     * （`JdbcPromptMetadataRepository`）はAggregate行が存在しない`key`に対しては
     * 例外を投げ、新規`prompts`行を作成しない（ADR-0020「Aggregate外の限定的なUPDATE」設計）。
     */
    fun upsert(
        metadata: PromptMetadata,
        events: List<PromptDomainEvent> = emptyList(),
    )
}
