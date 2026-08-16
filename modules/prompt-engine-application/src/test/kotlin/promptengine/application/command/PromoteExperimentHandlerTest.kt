package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentStatus
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.Variant
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.domain.template.TemplateRepository
import java.time.Instant
import java.util.UUID

/**
 * [PromoteExperimentHandler]のテスト（ADR-0034決定5・6、`Prompt.publish`との同一トランザクション
 * 整合をADR-0032決定1と同じパターンで検証する）。
 */
class PromoteExperimentHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private class NoDependencyRepository : DependencyRepository {
        override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun findOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
        ): List<DependencyEdge> = emptyList()

        override fun findInbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun findInboundTemplateOrFragment(
            kind: DependencyKind,
            key: String,
        ): List<DependencyEdge> = emptyList()

        override fun replaceOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
            edges: List<DependencyEdge>,
        ) = Unit
    }

    private fun approvedPrompt(): Prompt {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val inReview = created.submitForReview(semVer, validationPassed = true)
        return inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
    }

    private fun variant(
        name: String,
        weightPct: Int,
    ) = Variant(UUID.randomUUID(), name, semVer, weightPct)

    private fun runningExperiment(): Experiment {
        val draft =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 50), variant("treatment", 50)),
                TrafficPolicy(),
            )
        return draft.start(context).first
    }

    private fun handler(
        experimentRepository: InMemoryExperimentRepository,
        promptRepository: InMemoryPromptRepository,
    ) = PromoteExperimentHandler(
        experimentRepository,
        promptRepository,
        mockk<TemplateRepository>(relaxed = true),
        mockk<FragmentRepository>(relaxed = true),
        NoDependencyRepository(),
        PassthroughIdempotentCommandExecutor(),
    )

    @Test
    fun `勝者Variantを指定するとExperimentがCompletedへ遷移しPromptがPublishされる`() {
        val experiment = runningExperiment()
        val winnerName = experiment.variants.first().name
        val experimentRepository = InMemoryExperimentRepository().apply { seed(experiment) }
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }

        val result =
            handler(experimentRepository, promptRepository)
                .handle(PromoteExperimentCommand(experiment.experimentId, winnerName, "user:owner", "trace-1"))

        experimentRepository.findById(experiment.experimentId)!!.status shouldBe ExperimentStatus.Completed
        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.Published
        result.promotedSemVer shouldBe semVer.toString()
        experimentRepository.savedEvents.map { it.eventType } shouldBe
            listOf("ExperimentWinnerDeclared", "ExperimentCompleted")
    }

    @Test
    fun `存在しないVariant名を指定するとIllegalArgumentExceptionを投げPromptは変更されない`() {
        val experiment = runningExperiment()
        val experimentRepository = InMemoryExperimentRepository().apply { seed(experiment) }
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }

        shouldThrow<IllegalArgumentException> {
            handler(experimentRepository, promptRepository)
                .handle(PromoteExperimentCommand(experiment.experimentId, "unknown", "user:owner", "trace-1"))
        }
        promptRepository.findByKey(promptKey)!!.versions.single().state shouldBe LifecycleState.Approved
    }

    @Test
    fun `存在しないExperimentIdはExperimentNotFoundExceptionを投げる`() {
        val experimentRepository = InMemoryExperimentRepository()
        val promptRepository = InMemoryPromptRepository()

        shouldThrow<ExperimentNotFoundException> {
            handler(experimentRepository, promptRepository)
                .handle(PromoteExperimentCommand(UUID.randomUUID(), "control", "user:owner", "trace-1"))
        }
    }
}
