package promptengine.application.query

import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRepository
import promptengine.domain.shared.Page

/** `GET /audit-logs?aggregateId=&actor=&from=&to=`（設計書§13.1、監査検索）。 */
class AuditLogsHandler(
    private val auditRepository: AuditRepository,
) {
    fun handle(query: AuditQuery): Page<AuditLogEntry> = auditRepository.search(query)
}
