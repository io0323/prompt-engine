package promptengine.infrastructure.audit

import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import promptengine.domain.shared.Page
import java.util.Collections

/**
 * [AuditRepository]のインメモリ実装（ADR-0015決定7、ADR-0017）。プロセス内メモリのみに
 * 記録を保持し、再起動で失われる。ローカル開発・テスト用途に限定し、本番相当の永続化は
 * `JdbcAuditRepository`（ADR-0017、[Issue #35](https://github.com/io0323/prompt-engine/issues/35)
 * をクローズ）を使う。
 *
 * [activeProfiles]に`"production"`が含まれる場合、監査記録がプロセス再起動で失われる
 * ことは監査要件の欠落という重大な問題になるため、警告ログではなく起動時エラー
 * （[IllegalStateException]）とする。
 */
class InMemoryAuditRepository(activeProfiles: Set<String>) : AuditRepository {
    init {
        check(PRODUCTION_PROFILE !in activeProfiles) {
            "InMemoryAuditRepository must not be selected under the '$PRODUCTION_PROFILE' profile: " +
                "audit records would be lost on process restart. See JdbcAuditRepository."
        }
    }

    private val records = Collections.synchronizedList(mutableListOf<AuditRecord>())
    private val entries = Collections.synchronizedList(mutableListOf<AuditLogEntry>())

    override fun append(record: AuditRecord) {
        records += record
    }

    override fun record(entry: AuditLogEntry) {
        entries += entry
    }

    override fun search(query: AuditQuery): Page<AuditLogEntry> {
        val filtered =
            synchronized(entries) {
                entries
                    .filter { query.aggregateId == null || it.aggregateId == query.aggregateId }
                    .filter { query.actor == null || it.actor == query.actor }
                    .filter { query.from == null || !it.occurredAt.isBefore(query.from) }
                    .filter { query.to == null || !it.occurredAt.isAfter(query.to) }
                    .sortedByDescending { it.occurredAt }
            }
        // page/sizeはAuditQuery.initでpage>=0・size<=MAX_SIZEを保証されるが、pageの上限は
        // 無いため、page * sizeをIntのまま計算するとオーバーフローしうる（CodeRabbitレビュー指摘）。
        // Long演算で計算してからfiltered.sizeでクランプする。
        val fromIndex = (query.page.toLong() * query.size).coerceAtMost(filtered.size.toLong()).toInt()
        val toIndex = (fromIndex.toLong() + query.size).coerceAtMost(filtered.size.toLong()).toInt()
        return Page(
            items = filtered.subList(fromIndex, toIndex),
            page = query.page,
            size = query.size,
            totalElements = filtered.size.toLong(),
        )
    }

    /**
     * テスト・診断用に現在保持している記録のスナップショットを返す。
     *
     * [Collections.synchronizedList]は個々のメソッド呼出（`append`等）自体は同期するが、
     * `toList()`が内部で行うイテレーションはリストのモニタで保護されない。他スレッドが
     * `append`で並行して書き込むと`ConcurrentModificationException`が起こりうるため
     * （CodeRabbitレビュー指摘、P8）、`synchronized(records)`でイテレーション全体を囲む。
     */
    fun snapshot(): List<AuditRecord> = synchronized(records) { records.toList() }

    companion object {
        private const val PRODUCTION_PROFILE = "production"
    }
}
