package promptengine.domain.audit

/**
 * Audit記録の追記専用ストア（設計書§3.4疑似コード、§2.14、ADR-0015決定7）。
 *
 * §3.4疑似コードは`search(q: AuditQuery): Page<AuditRecord>`も定義するが、`AuditQuery`/
 * `Page<T>`のいずれも未設計であり、Pipeline Stage 12（Audit）は`append`のみを必要とする
 * ため、本Interfaceは`append`のみを持つ。`search`は
 * [Issue #35](https://github.com/io0323/prompt-engine/issues/35)で追加する。
 */
interface AuditRepository {
    /** [record]を追記する。実装は追記専用（更新・削除を提供しない）。 */
    fun append(record: AuditRecord)
}
