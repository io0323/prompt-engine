package promptengine.domain.governance

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.util.UUID

/** 永続化層からの [ReviewCase] 復元材料一式（P2のPromptMementoパターンを踏襲、ADR-0032）。 */
data class ReviewCaseMemento(
    val reviewId: UUID,
    val promptKey: PromptKey,
    val semVer: SemVer,
    val submittedBy: String,
    val requiredApprovals: Int,
    val status: ReviewCaseStatus,
    val approvals: List<ApprovalRecord>,
)
