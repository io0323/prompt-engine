package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties
import promptengine.domain.governance.ApprovalPolicy

/**
 * ReviewCaseの承認ポリシー設定（`promptengine.review.*`、設計書§2.5・§4.3、ADR-0032決定2/3）。
 *
 * [requiredApprovals] は`submitForReview`時点の値を`ReviewCase.requiredApprovals`として
 * 複製・保存する（ADR-0032決定2、以後この値を変更しても進行中のReviewCaseには遡及しない）。
 * [allowSelfApproval] は`review_cases`に対応する列を持たないため複製されず、`approve`のたびに
 * 現在の設定値を読む（[ApprovalPolicy]のKDoc参照）。
 */
@ConfigurationProperties(prefix = "promptengine.review")
data class ApprovalPolicyProperties(
    val requiredApprovals: Int = DEFAULT_REQUIRED_APPROVALS,
    val allowSelfApproval: Boolean = DEFAULT_ALLOW_SELF_APPROVAL,
) {
    init {
        require(requiredApprovals >= 1) {
            "promptengine.review.required-approvals must be at least 1: $requiredApprovals"
        }
    }

    /** ドメイン側の設定型へ変換する（ハンドラはSpringの設定バインディングを知らない）。 */
    fun toApprovalPolicy(): ApprovalPolicy = ApprovalPolicy(requiredApprovals, allowSelfApproval)

    companion object {
        private const val DEFAULT_REQUIRED_APPROVALS = 1
        private const val DEFAULT_ALLOW_SELF_APPROVAL = false
    }
}
