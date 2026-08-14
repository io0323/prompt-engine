package promptengine.infrastructure.persistence

import promptengine.domain.governance.ApprovalDecision
import promptengine.domain.governance.ReviewCaseStatus

/**
 * DB行の文字列表現とdomain型（[ReviewCaseStatus] / [ApprovalDecision]）の相互変換。
 * `internal`: `prompt-engine-infrastructure` モジュール内の永続化コードのみが使う（ADR-0032）。
 */
internal fun ReviewCaseStatus.toDbValue(): String =
    when (this) {
        ReviewCaseStatus.InReview -> "InReview"
        ReviewCaseStatus.Approved -> "Approved"
        ReviewCaseStatus.Rejected -> "Rejected"
    }

internal fun reviewCaseStatusFromDbValue(value: String): ReviewCaseStatus =
    when (value) {
        "InReview" -> ReviewCaseStatus.InReview
        "Approved" -> ReviewCaseStatus.Approved
        "Rejected" -> ReviewCaseStatus.Rejected
        else -> error("unknown ReviewCaseStatus value: $value")
    }

internal fun ApprovalDecision.toDbValue(): String = name

internal fun approvalDecisionFromDbValue(value: String): ApprovalDecision = ApprovalDecision.valueOf(value)
