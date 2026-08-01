package promptengine.domain.audit

import promptengine.domain.shared.Page
import java.time.Instant

/**
 * `GET /audit-logs` の検索条件（設計書§13.1、ADR-0017でIssue #35をクローズし追加）。
 *
 * `page`/`size`は[Page]と同じ範囲（`page >= 0`、`size in 1..MAX_SIZE`）を要求する
 * （CodeRabbitレビュー指摘: 未検証のまま巨大な`page`を許すと`page * size`がオーバーフローし
 * 負のOFFSETになりうる）。
 */
data class AuditQuery(
    val aggregateId: String? = null,
    val actor: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val page: Int = 0,
    val size: Int = Page.DEFAULT_SIZE,
) {
    init {
        require(page >= 0) { "page must not be negative: $page" }
        require(size in 1..Page.MAX_SIZE) { "size must be between 1 and ${Page.MAX_SIZE}: $size" }
    }
}
