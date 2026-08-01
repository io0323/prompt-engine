package promptengine.infrastructure.messaging

import promptengine.domain.event.DomainEvent
import promptengine.domain.event.EventBusAdapter
import java.util.Collections

/**
 * [EventBusAdapter]の最小実装（ADR-0015決定7）。プロセス内メモリのみに発行イベントを
 * 保持し、他プロセス・再起動を跨いだ購読はできない。
 * [Issue #35](https://github.com/io0323/prompt-engine/issues/35)で標準Broker Adapter実装
 * （設計書§16拡張ポイント#14）へ置き換える。
 *
 * [activeProfiles]に`"production"`が含まれる場合、起動時エラー（[IllegalStateException]）
 * とする（`InMemoryAuditRepository`と同じ一貫した扱い、ADR-0015決定7）。
 */
class InMemoryEventBusAdapter(activeProfiles: Set<String>) : EventBusAdapter {
    init {
        check(PRODUCTION_PROFILE !in activeProfiles) {
            "InMemoryEventBusAdapter must not be selected under the '$PRODUCTION_PROFILE' profile: " +
                "published events are not durable and have no real subscribers. See Issue #35."
        }
    }

    private val published = Collections.synchronizedList(mutableListOf<DomainEvent>())

    override fun publish(event: DomainEvent) {
        published += event
    }

    /**
     * テスト・診断用に現在保持している発行済みイベントのスナップショットを返す。
     *
     * [Collections.synchronizedList]は個々のメソッド呼出（`add`等）自体は同期するが、
     * `toList()`が内部で行うイテレーションはリストのモニタで保護されない。他スレッドが
     * `publish`で並行して書き込むと`ConcurrentModificationException`が起こりうるため
     * （CodeRabbitレビュー指摘）、`synchronized(published)`でイテレーション全体を囲む。
     */
    fun snapshot(): List<DomainEvent> = synchronized(published) { published.toList() }

    companion object {
        private const val PRODUCTION_PROFILE = "production"
    }
}
