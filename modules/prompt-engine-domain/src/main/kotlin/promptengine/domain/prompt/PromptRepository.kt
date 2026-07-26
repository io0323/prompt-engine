package promptengine.domain.prompt

/**
 * Prompt Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P2）で行う。
 *
 * `save` は現在状態（RDB投影）の保存と、Event Storeへの `events` 追記を
 * 同一トランザクションで行う（設計書§2.14・§3.4「Aggregate単位・イベント追記」、ADR-0006）。
 * `events` は `Prompt` の各操作メソッド（`publish`/`rollback`等）が返す
 * `PromptDomainEvent` をそのまま渡すことを想定し、`Prompt` 自身は発行イベントを
 * 保持しないためデフォルトは空リストとする。
 */
interface PromptRepository {
    fun findByKey(key: PromptKey): Prompt?

    fun save(
        prompt: Prompt,
        events: List<PromptDomainEvent> = emptyList(),
    ): Prompt
}
