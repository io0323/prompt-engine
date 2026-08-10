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
 *
 * [eventId]はBroker経由のイベントを起点に追記する場合（`AuditEngine`、P10b・ADR-0026決定4）の
 * 冪等キー。`audit_logs.event_id`（UNIQUE、V13）に対応し、`ON CONFLICT (event_id) DO NOTHING`で
 * 同一イベントの再配信が二重の監査行にならないことを保証する（ADR-0025決定8）。
 * CRUD/lifecycle系Commandハンドラ（P9、ADR-0017）の追記経路はキーにできるイベントを
 * 持たないため`null`のままとし、その場合は無条件のINSERTになる。
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
    val eventId: UUID? = null,
)
