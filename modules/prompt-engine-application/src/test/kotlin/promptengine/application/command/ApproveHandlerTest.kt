package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.governance.ApprovalPolicy
import promptengine.domain.governance.ReviewCase
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ApproveHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "user:author", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
    private val fixedClock = Clock.fixed(context.occurredAt, ZoneOffset.UTC)

    private fun inReviewPrompt(): Prompt {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        return created.submitForReview(semVer, validationPassed = true)
    }

    private fun handler(
        promptRepository: InMemoryPromptRepository,
        reviewCaseRepository: InMemoryReviewCaseRepository,
        allowSelfApproval: Boolean = false,
    ) = ApproveHandler(
        promptRepository,
        reviewCaseRepository,
        allowSelfApproval,
        PassthroughIdempotentCommandExecutor(),
        fixedClock,
    )

    @Test
    fun `必要承認数に達しない間はPromptをApprovedへ遷移させない`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(inReviewPrompt()) }
        val (reviewCase, _) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 2, allowSelfApproval = false),
                context,
            )
        val reviewCaseRepository = InMemoryReviewCaseRepository().apply { seed(reviewCase) }

        val result =
            handler(promptRepository, reviewCaseRepository)
                .handle(
                    ApproveCommand(promptKey, semVer, comment = null, actor = "user:approver1", traceId = "trace-1"),
                )

        result.key shouldBe promptKey
        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.InReview
        reviewCaseRepository.findInReview(promptKey, semVer)!!.approvalCount shouldBe 1
    }

    @Test
    fun `必要承認数に達するとPromptをApprovedへ遷移させ同一トランザクションで保存する`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(inReviewPrompt()) }
        val (created, _) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 2, allowSelfApproval = false),
                context,
            )
        val (afterFirst, _) =
            created.approve(
                "user:approver1",
                null,
                allowSelfApproval = false,
                context = context.copy(actor = "user:approver1"),
            )
        val reviewCaseRepository = InMemoryReviewCaseRepository().apply { seed(afterFirst) }

        handler(promptRepository, reviewCaseRepository)
            .handle(ApproveCommand(promptKey, semVer, comment = "lgtm", actor = "user:approver2", traceId = "trace-1"))

        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.Approved
        reviewCaseRepository.findInReview(promptKey, semVer) shouldBe null
    }

    @Test
    fun `allowSelfApprovalがfalseの場合に自己承認を拒否しPromptを変更しない`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(inReviewPrompt()) }
        val (reviewCase, _) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 1, allowSelfApproval = false),
                context,
            )
        val reviewCaseRepository = InMemoryReviewCaseRepository().apply { seed(reviewCase) }

        shouldThrow<InvalidStateTransitionException> {
            handler(promptRepository, reviewCaseRepository, allowSelfApproval = false)
                .handle(ApproveCommand(promptKey, semVer, comment = null, actor = "user:author", traceId = "trace-1"))
        }
        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.InReview
    }

    @Test
    fun `allowSelfApprovalがtrueの場合に自己承認を許可する`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(inReviewPrompt()) }
        val (reviewCase, _) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 1, allowSelfApproval = true),
                context,
            )
        val reviewCaseRepository = InMemoryReviewCaseRepository().apply { seed(reviewCase) }

        handler(promptRepository, reviewCaseRepository, allowSelfApproval = true)
            .handle(ApproveCommand(promptKey, semVer, comment = null, actor = "user:author", traceId = "trace-1"))

        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.Approved
    }

    @Test
    fun `InReviewなReviewCaseが無ければInvalidStateTransitionExceptionを投げる`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(inReviewPrompt()) }

        shouldThrow<InvalidStateTransitionException> {
            handler(promptRepository, InMemoryReviewCaseRepository())
                .handle(
                    ApproveCommand(promptKey, semVer, comment = null, actor = "user:approver1", traceId = "trace-1"),
                )
        }
    }
}
