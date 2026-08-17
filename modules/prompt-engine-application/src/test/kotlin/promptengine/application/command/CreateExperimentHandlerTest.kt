package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.experiment.ExperimentStatus
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import promptengine.domain.shared.SemVer
import java.time.Instant

class CreateExperimentHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun handler(
        promptRepository: InMemoryPromptRepository,
        experimentRepository: InMemoryExperimentRepository,
    ) = CreateExperimentHandler(promptRepository, experimentRepository, PassthroughIdempotentCommandExecutor())

    private fun approvedPrompt(): Prompt {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val inReview = created.submitForReview(semVer, validationPassed = true)
        return inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
    }

    private fun command(
        weights: List<VariantInput> =
            listOf(
                VariantInput("control", semVer, 50),
                VariantInput("treatment", semVer, 50),
            ),
    ) = CreateExperimentCommand(
        promptKey,
        ExperimentType.AB,
        weights,
        stickyKeyPath = null,
        actor = "user:owner",
        traceId = "trace-1",
    )

    @Test
    fun `Approved状態のVersionを参照するVariantでExperimentをDraftで作成する`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val experimentRepository = InMemoryExperimentRepository()

        val result = handler(promptRepository, experimentRepository).handle(command())

        val saved = experimentRepository.findById(result.experimentId)!!
        saved.status shouldBe ExperimentStatus.Draft
        saved.variants.map { it.name }.toSet() shouldBe setOf("control", "treatment")
        result.promptKey shouldBe promptKey.value
    }

    @Test
    fun `Draft状態のVersionを参照するVariantはPromptVersionStateNotAllowedExceptionで拒否する`() {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(created) }
        val experimentRepository = InMemoryExperimentRepository()

        shouldThrow<PromptVersionStateNotAllowedException> {
            handler(promptRepository, experimentRepository).handle(command())
        }
    }

    @Test
    fun `存在しないPromptKeyはPromptVersionNotFoundExceptionを投げる`() {
        val promptRepository = InMemoryPromptRepository()
        val experimentRepository = InMemoryExperimentRepository()

        shouldThrow<PromptVersionNotFoundException> {
            handler(promptRepository, experimentRepository).handle(command())
        }
    }

    @Test
    fun `存在しないVersionを参照するVariantはPromptVersionNotFoundExceptionを投げる`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val experimentRepository = InMemoryExperimentRepository()
        val badCommand =
            command(listOf(VariantInput("control", SemVer(9, 9, 9), 50), VariantInput("treatment", semVer, 50)))

        shouldThrow<PromptVersionNotFoundException> {
            handler(promptRepository, experimentRepository).handle(badCommand)
        }
    }
}
