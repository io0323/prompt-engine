package promptengine.domain.governance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.SemVer
import java.time.Instant

/**
 * ReviewCase Aggregate のテスト（設計書§4.3 不変条件「承認数 ≥ ApprovalPolicy.required で
 * 承認確定」、ADR-0032）。
 */
class ReviewCaseTest {
    private val promptKey = PromptKey("support/faq-answer")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "user:author", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun approverContext(actor: String) = context.copy(actor = actor)

    private fun createInReview(requiredApprovals: Int = 1): ReviewCase =
        ReviewCase.create(
            promptKey,
            semVer,
            ApprovalPolicy(requiredApprovals, allowSelfApproval = false),
            context,
        ).first

    @Test
    fun `create はInReview状態のReviewCaseを生成しPromptReviewRequestedを発行する`() {
        val (reviewCase, event) =
            ReviewCase.create(promptKey, semVer, ApprovalPolicy(1, allowSelfApproval = false), context)

        reviewCase.status shouldBe ReviewCaseStatus.InReview
        reviewCase.promptKey shouldBe promptKey
        reviewCase.semVer shouldBe semVer
        reviewCase.submittedBy shouldBe context.actor
        reviewCase.requiredApprovals shouldBe 1
        reviewCase.approvals shouldBe emptyList()

        event.eventType shouldBe "PromptReviewRequested"
        event.aggregateType shouldBe "ReviewCase"
        event.aggregateId shouldBe reviewCase.reviewId.toString()
        event.actor shouldBe context.actor
        event.traceId shouldBe context.traceId
        event.occurredAt shouldBe context.occurredAt
        event.payload.promptKey shouldBe promptKey.value
        event.payload.semVer shouldBe semVer
    }

    @Test
    fun `approve は必要承認数に達しない間はInReviewのままイベントを発行しない`() {
        val reviewCase = createInReview(requiredApprovals = 2)

        val (updated, event) =
            reviewCase.approve(
                "user:approver1",
                comment = null,
                allowSelfApproval = false,
                context = approverContext("user:approver1"),
            )

        updated.status shouldBe ReviewCaseStatus.InReview
        updated.approvalCount shouldBe 1
        event.shouldBeNull()
    }

    @Test
    fun `approve は必要承認数に達するとApprovedへ遷移しPromptApprovedを発行する`() {
        val reviewCase = createInReview(requiredApprovals = 2)
        val (afterFirst, _) =
            reviewCase.approve(
                "user:approver1",
                null,
                allowSelfApproval = false,
                context = approverContext("user:approver1"),
            )

        val (updated, event) =
            afterFirst.approve(
                "user:approver2",
                "looks good",
                allowSelfApproval = false,
                context = approverContext("user:approver2"),
            )

        updated.status shouldBe ReviewCaseStatus.Approved
        updated.approvalCount shouldBe 2
        event.shouldNotBeNull()
        event.eventType shouldBe "PromptApproved"
        event.aggregateId shouldBe reviewCase.reviewId.toString()
        event.actor shouldBe "user:approver2"
        event.payload.promptKey shouldBe promptKey.value
        event.payload.semVer shouldBe semVer
    }

    @Test
    fun `approve は自己承認をallowSelfApprovalがfalseの場合に拒否する`() {
        val reviewCase = createInReview()

        val ex =
            shouldThrow<InvalidStateTransitionException> {
                reviewCase.approve(context.actor, null, allowSelfApproval = false, context = context)
            }
        ex.message shouldBe "cannot perform 'approve' from state 'SelfApproval'"
    }

    @Test
    fun `approve は自己承認をallowSelfApprovalがtrueの場合に許可する`() {
        val reviewCase = createInReview(requiredApprovals = 1)

        val (updated, event) = reviewCase.approve(context.actor, null, allowSelfApproval = true, context = context)

        updated.status shouldBe ReviewCaseStatus.Approved
        event.shouldNotBeNull()
    }

    @Test
    fun `approve はallowSelfApprovalがtrueでも別approverによる承認を正しく記録する`() {
        val reviewCase = createInReview(requiredApprovals = 1)

        val (updated, event) =
            reviewCase.approve(
                "user:approver1",
                null,
                allowSelfApproval = true,
                context = approverContext("user:approver1"),
            )

        updated.status shouldBe ReviewCaseStatus.Approved
        event.shouldNotBeNull()
        event.actor shouldBe "user:approver1"
    }

    @Test
    fun `approve は同一approverによる重複承認を拒否する`() {
        val reviewCase = createInReview(requiredApprovals = 2)
        val (afterFirst, _) =
            reviewCase.approve(
                "user:approver1",
                null,
                allowSelfApproval = false,
                context = approverContext("user:approver1"),
            )

        val ex =
            shouldThrow<InvalidStateTransitionException> {
                afterFirst.approve(
                    "user:approver1",
                    null,
                    allowSelfApproval = false,
                    context = approverContext("user:approver1"),
                )
            }
        ex.message shouldBe "cannot perform 'approve' from state 'DuplicateApproval'"
    }

    @Test
    fun `approve はInReview以外の状態から呼ぶとInvalidStateTransitionExceptionを投げる`() {
        val reviewCase = createInReview(requiredApprovals = 1)
        val (approved, _) = reviewCase.approve(context.actor, null, allowSelfApproval = true, context = context)

        shouldThrow<InvalidStateTransitionException> {
            approved.approve(
                "user:approver2",
                null,
                allowSelfApproval = false,
                context = approverContext("user:approver2"),
            )
        }
    }

    @Test
    fun `reject はDraftへの差戻しとしてRejectedへ遷移しPromptRejectedを発行する`() {
        val reviewCase = createInReview()

        val (updated, event) =
            reviewCase.reject(
                "user:reviewer",
                "not aligned with policy",
                context = approverContext("user:reviewer"),
            )

        updated.status shouldBe ReviewCaseStatus.Rejected
        event.eventType shouldBe "PromptRejected"
        event.aggregateId shouldBe reviewCase.reviewId.toString()
        event.actor shouldBe "user:reviewer"
        event.payload.comment shouldBe "not aligned with policy"
    }

    @Test
    fun `reject は空白のみのcommentをIllegalArgumentExceptionで拒否する`() {
        val reviewCase = createInReview()

        shouldThrow<IllegalArgumentException> {
            reviewCase.reject("user:reviewer", "   ", context = approverContext("user:reviewer"))
        }
    }

    @Test
    fun `reject はInReview以外の状態から呼ぶとInvalidStateTransitionExceptionを投げる`() {
        val reviewCase = createInReview(requiredApprovals = 1)
        val (approved, _) = reviewCase.approve(context.actor, null, allowSelfApproval = true, context = context)

        shouldThrow<InvalidStateTransitionException> {
            approved.reject("user:reviewer", "too late", context = approverContext("user:reviewer"))
        }
    }

    @Test
    fun `restore はPersistenceApi経由でMementoの内容をそのまま復元する`() {
        val reviewId = createInReview().reviewId
        val memento =
            ReviewCaseMemento(
                reviewId = reviewId,
                promptKey = promptKey,
                semVer = semVer,
                submittedBy = "user:author",
                requiredApprovals = 2,
                status = ReviewCaseStatus.Approved,
                approvals =
                    listOf(
                        ApprovalRecord("user:a", ApprovalDecision.APPROVED, null, context.occurredAt),
                        ApprovalRecord("user:b", ApprovalDecision.APPROVED, "ok", context.occurredAt),
                    ),
            )

        @OptIn(promptengine.domain.shared.PersistenceApi::class)
        val restored = ReviewCase.restore(memento)

        restored.reviewId shouldBe reviewId
        restored.status shouldBe ReviewCaseStatus.Approved
        restored.approvalCount shouldBe 2
    }

    @Test
    fun `approvalCount はREJECTEDの記録を数に含めない`() {
        val memento =
            ReviewCaseMemento(
                reviewId = createInReview().reviewId,
                promptKey = promptKey,
                semVer = semVer,
                submittedBy = "user:author",
                requiredApprovals = 2,
                status = ReviewCaseStatus.InReview,
                approvals =
                    listOf(
                        ApprovalRecord("user:a", ApprovalDecision.APPROVED, null, context.occurredAt),
                        ApprovalRecord("user:b", ApprovalDecision.REJECTED, "no", context.occurredAt),
                    ),
            )

        @OptIn(promptengine.domain.shared.PersistenceApi::class)
        val restored = ReviewCase.restore(memento)

        restored.approvalCount shouldBe 1
    }
}
