package promptengine.domain.governance

import promptengine.domain.event.EventContext
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * Governanceコンテキストの Aggregate Root（設計書§2.5・§4.1・§4.3、ADR-0004、ADR-0032）。
 *
 * `submitForReview`/`approve`/`reject`に対応するDomain Event
 * （`PromptReviewRequested`/`PromptApproved`/`PromptRejected`、設計書§14）の発火元は
 * `Prompt`ではなく本Aggregateである（ADR-0004）。`Prompt`側のLifecycleState遷移自体は
 * `Prompt.submitForReview`/`approve`/`reject`が別途行う。両Aggregateの整合は、
 * Application層のハンドラが同一DBトランザクションで両方を保存することで取る
 * （ADR-0032決定1、Aggregate単位のトランザクションという原則からの意図的かつ限定的な逸脱）。
 *
 * 却下（[reject]）されたレビューは終端であり、再度`submitForReview`した場合は
 * 新規`ReviewCase`（新しい[reviewId]）を作成する。同一行を`InReview`へ戻して再利用しない
 * （設計書§12のER図が`prompt_versions ||--o{ review_cases`と0..多の関係を定義しているため。
 * ADR-0032決定4）。
 *
 * プライマリコンストラクタは `internal`。新規作成は [create]、永続化層からの復元は
 * [restore] を使うこと（`Prompt`と同じ規約、ADR-0006）。
 */
@ConsistentCopyVisibility
data class ReviewCase internal constructor(
    val reviewId: UUID,
    val promptKey: PromptKey,
    val semVer: SemVer,
    val submittedBy: String,
    val requiredApprovals: Int,
    val status: ReviewCaseStatus,
    val approvals: List<ApprovalRecord>,
) {
    /** 承認とみなす件数。同一approverの重複承認は1件として数える（[approve]が事前に拒否するため実際には発生しない）。 */
    val approvalCount: Int
        get() = approvals.filter { it.decision == ApprovalDecision.APPROVED }.map { it.approver }.distinct().size

    companion object {
        /** Draft→InReview提出。[EventContext.actor] が [submittedBy]（4-eyes判定の基準）になる。 */
        fun create(
            promptKey: PromptKey,
            semVer: SemVer,
            policy: ApprovalPolicy,
            context: EventContext,
        ): Pair<ReviewCase, PromptReviewRequested> {
            val reviewId = UUID.randomUUID()
            val reviewCase =
                ReviewCase(
                    reviewId = reviewId,
                    promptKey = promptKey,
                    semVer = semVer,
                    submittedBy = context.actor,
                    requiredApprovals = policy.requiredApprovals,
                    status = ReviewCaseStatus.InReview,
                    approvals = emptyList(),
                )
            val event =
                PromptReviewRequested(
                    eventId = UUID.randomUUID(),
                    occurredAt = context.occurredAt,
                    aggregateId = reviewId.toString(),
                    actor = context.actor,
                    traceId = context.traceId,
                    payload = PromptReviewRequested.Payload(promptKey.value, semVer),
                )
            return reviewCase to event
        }

        /**
         * 永続化層からの復元専用（P2の`Prompt.restore`パターンを踏襲）。
         * `prompt-engine-infrastructure`の`promptengine.infrastructure.persistence`
         * パッケージ以外からの呼び出しは`@PersistenceApi`とArchUnitルールの二重で防ぐ。
         */
        @PersistenceApi
        fun restore(memento: ReviewCaseMemento): ReviewCase =
            ReviewCase(
                memento.reviewId,
                memento.promptKey,
                memento.semVer,
                memento.submittedBy,
                memento.requiredApprovals,
                memento.status,
                memento.approvals,
            )
    }

    /**
     * 承認を1件記録する。必要承認数（[requiredApprovals]）に達した場合のみ[PromptApproved]を
     * 返す（達しない場合は`null`。呼出側は`listOfNotNull`で events リストへ渡す想定）。
     *
     * @param allowSelfApproval 現在のグローバル設定値（[ApprovalPolicy]のKDoc参照、
     *   `requiredApprovals`と異なりReviewCase自身には複製・保存しない）
     */
    fun approve(
        approver: String,
        comment: String?,
        allowSelfApproval: Boolean,
        context: EventContext,
    ): Pair<ReviewCase, PromptApproved?> {
        assertCanApprove(approver, allowSelfApproval)

        val newRecord = ApprovalRecord(approver, ApprovalDecision.APPROVED, comment, context.occurredAt)
        val updatedApprovals = approvals + newRecord
        val newApprovalCount = updatedApprovals.count { it.decision == ApprovalDecision.APPROVED }

        return if (newApprovalCount >= requiredApprovals) {
            val updated = copy(status = ReviewCaseStatus.Approved, approvals = updatedApprovals)
            val event =
                PromptApproved(
                    eventId = UUID.randomUUID(),
                    occurredAt = context.occurredAt,
                    aggregateId = reviewId.toString(),
                    actor = context.actor,
                    traceId = context.traceId,
                    payload = PromptApproved.Payload(promptKey.value, semVer),
                )
            updated to event
        } else {
            copy(approvals = updatedApprovals) to null
        }
    }

    /**
     * [approve]のガード条件をまとめる（detektの`ThrowsCount`閾値を超えないよう2関数に分離）。
     */
    private fun assertCanApprove(
        approver: String,
        allowSelfApproval: Boolean,
    ) {
        if (status != ReviewCaseStatus.InReview) {
            throw InvalidStateTransitionException(status::class.simpleName ?: "Unknown", "approve")
        }
        assertApproverEligible(approver, allowSelfApproval)
    }

    private fun assertApproverEligible(
        approver: String,
        allowSelfApproval: Boolean,
    ) {
        if (!allowSelfApproval && approver == submittedBy) {
            throw InvalidStateTransitionException("SelfApproval", "approve")
        }
        if (approvals.any { it.decision == ApprovalDecision.APPROVED && it.approver == approver }) {
            throw InvalidStateTransitionException("DuplicateApproval", "approve")
        }
    }

    /** 差戻し。[comment] は設計書§13.1「差戻し（comment必須）」により必須（空白のみは不可）。 */
    fun reject(
        approver: String,
        comment: String,
        context: EventContext,
    ): Pair<ReviewCase, PromptRejected> {
        require(comment.isNotBlank()) { "reject requires a non-blank comment" }
        if (status != ReviewCaseStatus.InReview) {
            throw InvalidStateTransitionException(status::class.simpleName ?: "Unknown", "reject")
        }

        val newRecord = ApprovalRecord(approver, ApprovalDecision.REJECTED, comment, context.occurredAt)
        val updated = copy(status = ReviewCaseStatus.Rejected, approvals = approvals + newRecord)
        val event =
            PromptRejected(
                eventId = UUID.randomUUID(),
                occurredAt = context.occurredAt,
                aggregateId = reviewId.toString(),
                actor = context.actor,
                traceId = context.traceId,
                payload = PromptRejected.Payload(promptKey.value, semVer, comment),
            )
        return updated to event
    }
}
