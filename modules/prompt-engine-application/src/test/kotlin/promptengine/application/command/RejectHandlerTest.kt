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

class RejectHandlerTest {
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
    ) = RejectHandler(promptRepository, reviewCaseRepository, PassthroughIdempotentCommandExecutor(), fixedClock)

    @Test
    fun `rejectでPromptをDraftへ差し戻しReviewCaseをRejectedへ遷移させる`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(inReviewPrompt()) }
        val (reviewCase, _) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 1, allowSelfApproval = false),
                context,
            )
        val reviewCaseRepository = InMemoryReviewCaseRepository().apply { seed(reviewCase) }

        val result =
            handler(promptRepository, reviewCaseRepository)
                .handle(
                    RejectCommand(
                        promptKey,
                        semVer,
                        comment = "needs work",
                        actor = "user:reviewer",
                        traceId = "trace-1",
                    ),
                )

        result.key shouldBe promptKey
        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.Draft
        reviewCaseRepository.findInReview(promptKey, semVer) shouldBe null
    }

    @Test
    fun `InReviewなReviewCaseが無ければInvalidStateTransitionExceptionを投げPromptを変更しない`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(inReviewPrompt()) }

        shouldThrow<InvalidStateTransitionException> {
            handler(promptRepository, InMemoryReviewCaseRepository())
                .handle(
                    RejectCommand(
                        promptKey,
                        semVer,
                        comment = "needs work",
                        actor = "user:reviewer",
                        traceId = "trace-1",
                    ),
                )
        }
        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.InReview
    }
}
