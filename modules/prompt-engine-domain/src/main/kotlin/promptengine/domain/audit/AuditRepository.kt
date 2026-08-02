package promptengine.domain.audit

import promptengine.domain.shared.Page

/**
 * Audit記録の追記専用ストア（設計書§3.4疑似コード、§2.14、ADR-0015決定7、ADR-0017）。
 *
 * [append]はPipeline Stage 12専用（ADR-0015決定7）。[record]はCRUD/lifecycle系の
 * Commandハンドラ（P9）が使う一般形（ADR-0017）。[search]は両方の書き込み経路の行を
 * [AuditLogEntry]として返す（[Issue #35](https://github.com/io0323/prompt-engine/issues/35)を
 * ADR-0017でクローズ）。
 */
interface AuditRepository {
    /** [record]を追記する。実装は追記専用（更新・削除を提供しない）。 */
    fun append(record: AuditRecord)

    /** [entry]を追記する。実装は追記専用（更新・削除を提供しない）。 */
    fun record(entry: AuditLogEntry)

    /** [query]に一致する監査エントリをページングして返す。 */
    fun search(query: AuditQuery): Page<AuditLogEntry>
}
