package promptengine.domain.audit

import promptengine.domain.shared.Page
import java.time.Instant

/**
 * `GET /audit-logs` の検索条件（設計書§13.1、ADR-0017でIssue #35をクローズし追加）。
 */
data class AuditQuery(
    val aggregateId: String? = null,
    val actor: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val page: Int = 0,
    val size: Int = Page.DEFAULT_SIZE,
)
