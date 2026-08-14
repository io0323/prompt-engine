package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.Test
import promptengine.application.pipeline.ReviewValidationGate
import promptengine.domain.event.EventContext
import promptengine.domain.governance.ApprovalPolicy
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationFailedException
import promptengine.domain.validation.ValidationReport
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SubmitReviewHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "user:author", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun handler(
        promptRepository: InMemoryPromptRepository,
        reviewCaseRepository: InMemoryReviewCaseRepository,
        gate: ReviewValidationGate,
        policy: ApprovalPolicy = ApprovalPolicy(requiredApprovals = 1, allowSelfApproval = false),
    ) = SubmitReviewHandler(
        promptRepository,
        reviewCaseRepository,
        gate,
        policy,
        PassthroughIdempotentCommandExecutor(),
        clock = Clock.fixed(context.occurredAt, ZoneOffset.UTC),
    )

    @Test
    fun `Validation合格時はPromptをInReviewへ遷移させReviewCaseをInReviewで作成する`() {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(created) }
        val reviewCaseRepository = InMemoryReviewCaseRepository()
        val gate = mockk<ReviewValidationGate>()
        every { gate.assertValidationPassed(promptKey, semVer, "trace-1") } just Runs

        val result =
            handler(promptRepository, reviewCaseRepository, gate, ApprovalPolicy(2, allowSelfApproval = false))
                .handle(SubmitReviewCommand(promptKey, semVer, actor = "user:author", traceId = "trace-1"))

        result.key shouldBe promptKey
        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.InReview
        val reviewCase = reviewCaseRepository.findInReview(promptKey, semVer)!!
        reviewCase.requiredApprovals shouldBe 2
        reviewCase.submittedBy shouldBe "user:author"
    }

    @Test
    fun `Validation不合格時は例外が伝播しPromptもReviewCaseも変更されない`() {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(created) }
        val reviewCaseRepository = InMemoryReviewCaseRepository()
        val gate = mockk<ReviewValidationGate>()
        val report =
            ValidationReport(listOf(Finding(ruleId = "R1", path = "$.x", severity = Severity.ERROR, message = "bad")))
        every { gate.assertValidationPassed(promptKey, semVer, "trace-1") } throws ValidationFailedException(report)

        shouldThrow<ValidationFailedException> {
            handler(promptRepository, reviewCaseRepository, gate)
                .handle(SubmitReviewCommand(promptKey, semVer, actor = "user:author", traceId = "trace-1"))
        }

        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.Draft
        reviewCaseRepository.findInReview(promptKey, semVer) shouldBe null
    }

    @Test
    fun `Promptが存在しなければ例外を投げる`() {
        val gate = mockk<ReviewValidationGate>()

        shouldThrow<PromptVersionNotFoundException> {
            handler(InMemoryPromptRepository(), InMemoryReviewCaseRepository(), gate)
                .handle(SubmitReviewCommand(promptKey, semVer, actor = "user:author", traceId = "trace-1"))
        }
    }
}
