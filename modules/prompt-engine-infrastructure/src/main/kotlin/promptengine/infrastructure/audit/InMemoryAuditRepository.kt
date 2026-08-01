package promptengine.infrastructure.audit

import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import java.util.Collections

/**
 * [AuditRepository]の最小実装（ADR-0015決定7）。プロセス内メモリのみに記録を保持し、
 * 再起動で失われる。[Issue #35](https://github.com/io0323/prompt-engine/issues/35)で
 * 永続化実装へ置き換える。
 *
 * [activeProfiles]に`"production"`が含まれる場合、監査記録がプロセス再起動で失われる
 * ことは監査要件の欠落という重大な問題になるため、警告ログではなく起動時エラー
 * （[IllegalStateException]）とする。
 */
class InMemoryAuditRepository(activeProfiles: Set<String>) : AuditRepository {
    init {
        check(PRODUCTION_PROFILE !in activeProfiles) {
            "InMemoryAuditRepository must not be selected under the '$PRODUCTION_PROFILE' profile: " +
                "audit records would be lost on process restart. See Issue #35."
        }
    }

    private val records = Collections.synchronizedList(mutableListOf<AuditRecord>())

    override fun append(record: AuditRecord) {
        records += record
    }

    /**
     * テスト・診断用に現在保持している記録のスナップショットを返す。
     *
     * [Collections.synchronizedList]は個々のメソッド呼出（`append`等）自体は同期するが、
     * `toList()`が内部で行うイテレーションはリストのモニタで保護されない。他スレッドが
     * `append`で並行して書き込むと`ConcurrentModificationException`が起こりうるため
     * （CodeRabbitレビュー指摘）、`synchronized(records)`でイテレーション全体を囲む。
     */
    fun snapshot(): List<AuditRecord> = synchronized(records) { records.toList() }

    companion object {
        private const val PRODUCTION_PROFILE = "production"
    }
}
