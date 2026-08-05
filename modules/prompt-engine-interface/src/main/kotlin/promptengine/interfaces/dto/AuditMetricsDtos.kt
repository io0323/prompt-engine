package promptengine.interfaces.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.format.annotation.DateTimeFormat
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * `GET /audit-logs`のクエリパラメータ（設計書§13.1）を`@ModelAttribute`でまとめて受け取る
 * （[PromptSearchQueryParams]のKDoc参照、detekt LongParameterList対策）。
 */
data class AuditLogQueryParams(
    val aggregateId: String? = null,
    val actor: String? = null,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val from: Instant? = null,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val to: Instant? = null,
    @field:Min(0) val page: Int = 0,
    @field:Min(1) @field:Max(MAX_PAGE_SIZE) val size: Int = 20,
)

data class AuditLogEntryDto(
    val auditId: UUID,
    val aggregateType: String,
    val aggregateId: String,
    val action: String,
    val actor: String,
    val payload: String,
    val traceId: String,
    val occurredAt: Instant,
)

data class MetricsSummaryDto(
    val promptKey: String,
    val from: Instant,
    val to: Instant,
    val executionCount: Long,
    val successCount: Long,
    val successRate: Double,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCost: BigDecimal,
    val averageLatencyMs: Long,
)
