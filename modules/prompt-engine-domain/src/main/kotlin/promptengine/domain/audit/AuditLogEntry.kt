package promptengine.domain.audit

import java.time.Instant
import java.util.UUID

/**
 * `audit_logs`（設計書§12）の行に対応する一般形の監査エントリ（ADR-0017）。
 *
 * Pipeline実行専用の狭い形を持つ[AuditRecord]（ADR-0015決定7）とは別に、CRUD/lifecycle系の
 * Commandハンドラ（P9）が[AuditRepository.record]で追記する。`GET /audit-logs`
 * （[AuditRepository.search]）は[AuditRecord]由来・[AuditLogEntry]由来のいずれの行も
 * この形で返す。
 *
 * [payload]はSecretマスク済のJSON文字列であることを呼出側が保証する（CLAUDE.md
 * 「Secret/sensitive=trueの変数値は絶対に出力しない」）。
 */
data class AuditLogEntry(
    val auditId: UUID,
    val aggregateType: String,
    val aggregateId: String,
    val action: String,
    val actor: String,
    val payload: String,
    val traceId: String,
    val occurredAt: Instant,
)
