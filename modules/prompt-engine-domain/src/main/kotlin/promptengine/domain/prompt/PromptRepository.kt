package promptengine.domain.prompt

/**
 * Prompt Aggregateの永続化インターフェース。実装は `prompt-engine-infrastructure`（P2）で行う。
 */
interface PromptRepository {
    fun findByKey(key: PromptKey): Prompt?

    /**
     * 現在状態（RDB投影）の保存と、Event Storeへの [events] 追記を
     * 同一トランザクションで行う（設計書§2.14・§3.4「Aggregate単位・イベント追記」、ADR-0006）。
     *
     * @param prompt 保存するAggregateの現在状態
     * @param events このAggregateが今回の操作で発行したイベント（`Prompt` の各操作メソッド
     *   `publish`/`rollback`等が返す `PromptDomainEvent` をそのまま渡す想定）。`Prompt` 自身は
     *   発行イベントを保持しないためデフォルトは空リスト
     * @return 保存後の `Prompt`。`rowVersion` はDBに実際に反映された値を反映する
     */
    fun save(
        prompt: Prompt,
        events: List<PromptDomainEvent> = emptyList(),
    ): Prompt
}
