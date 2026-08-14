package promptengine.domain.governance

import java.time.Instant

/**
 * `ReviewCase` Aggregate内のEntity（設計書§4.3）。1件の承認/却下の意思表示（`approvals`テーブル、
 * 設計書§12）。§4.3が別掲する「ReviewComment」は、このスキーマでは独立したテーブルを持たず
 * [comment] として各決定に付随する形でのみ表現される（却下時は必須、承認時は任意）。
 */
data class ApprovalRecord(
    val approver: String,
    val decision: ApprovalDecision,
    val comment: String?,
    val decidedAt: Instant,
)
