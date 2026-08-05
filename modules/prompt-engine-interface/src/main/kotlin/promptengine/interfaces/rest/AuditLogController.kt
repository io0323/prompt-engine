package promptengine.interfaces.rest

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import promptengine.application.query.AuditLogsHandler
import promptengine.application.view.Paging
import promptengine.application.view.TimeRange
import promptengine.application.view.handleView
import promptengine.interfaces.dto.AuditLogEntryDto
import promptengine.interfaces.dto.AuditLogQueryParams
import promptengine.interfaces.dto.PageDto

/** `GET /audit-logs?aggregateId=&actor=&from=&to=`（設計書§13.1、監査検索）。 */
@RestController
@RequestMapping("/api/v1/audit-logs")
class AuditLogController(private val auditLogsHandler: AuditLogsHandler) {
    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    fun search(
        @ModelAttribute params: AuditLogQueryParams,
    ): PageDto<AuditLogEntryDto> {
        val view =
            auditLogsHandler.handleView(
                params.aggregateId,
                params.actor,
                TimeRange(params.from, params.to),
                Paging(params.page, params.size),
            )
        return PageDto(
            view.items.map {
                AuditLogEntryDto(
                    it.auditId,
                    it.aggregateType,
                    it.aggregateId,
                    it.action,
                    it.actor,
                    it.payload,
                    it.traceId,
                    it.occurredAt,
                )
            },
            view.page,
            view.size,
            view.totalElements,
        )
    }
}
