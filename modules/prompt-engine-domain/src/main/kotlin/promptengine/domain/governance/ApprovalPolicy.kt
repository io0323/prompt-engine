package promptengine.domain.governance

/**
 * 必要承認数・自己承認可否のポリシー（設計書§4.3「ApprovalPolicy.required」、ADR-0032決定2/3）。
 *
 * グローバル設定（`promptengine.review.*`）から構築し、[promptengine.domain.governance.ReviewCase.create]
 * に渡す。[requiredApprovals] は`ReviewCase`自身に`required_approvals`として複製・保存され
 * （設計書§12）、以後はこのポリシーの現在値ではなく複製された値を使う（ADR-0032決定2、
 * 進行中のReviewCaseへ遡及させないため）。
 *
 * [allowSelfApproval] は`review_cases`テーブルに対応する列を持たないため複製されず、
 * `approve`のたびに現在の設定値を読む（ADR-0032決定3の脚注: 設定を締める方向の変更が
 * 進行中のReviewCaseへ即時反映されるのは安全側であるため、この非対称は許容する）。
 */
data class ApprovalPolicy(
    val requiredApprovals: Int,
    val allowSelfApproval: Boolean,
) {
    init {
        require(requiredApprovals >= 1) { "requiredApprovals must be at least 1" }
    }
}
