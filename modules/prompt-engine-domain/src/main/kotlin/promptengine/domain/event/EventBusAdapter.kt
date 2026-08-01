package promptengine.domain.event

/**
 * Domain Eventの発行先（設計書§16拡張ポイント#14、ADR-0015決定7）。
 *
 * Pipeline Stage 11（Evaluation）が「イベント発行のみ（非同期評価）」を実現するために
 * 参照する。実装（標準Broker Adapter）は`prompt-engine-infrastructure`に置く。
 * M1時点の最小実装（`InMemoryEventBusAdapter`）は[GitHub Issue #35](https://github.com/io0323/prompt-engine/issues/35)で
 * 本実装へ置き換える。
 */
interface EventBusAdapter {
    /** [event]を発行する。呼出元（Pipeline Stage）の処理をブロックしない契約とする。 */
    fun publish(event: DomainEvent)
}
