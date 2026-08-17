package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentStatus
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.Variant
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

class StartExperimentHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private fun variant(
        name: String,
        weightPct: Int,
    ) = Variant(UUID.randomUUID(), name, SemVer(1, 0, 0), weightPct)

    private fun draftExperiment() =
        Experiment.create(
            promptKey,
            ExperimentType.AB,
            listOf(variant("control", 50), variant("treatment", 50)),
            TrafficPolicy(),
        )

    private fun handler(repository: InMemoryExperimentRepository) =
        StartExperimentHandler(repository, PassthroughIdempotentCommandExecutor())

    @Test
    fun `DraftのExperimentをRunningへ遷移させる`() {
        val experiment = draftExperiment()
        val repository = InMemoryExperimentRepository().apply { seed(experiment) }

        val result =
            handler(
                repository,
            ).handle(StartExperimentCommand(experiment.experimentId, "user:owner", "trace-1"))

        repository.findById(result.experimentId)!!.status shouldBe ExperimentStatus.Running
        repository.savedEvents.map { it.eventType } shouldBe listOf("ExperimentStarted")
    }

    @Test
    fun `同一PromptKeyに既にRunning中のExperimentがあれば拒否する`() {
        val running = draftExperiment().start(EventContext("a", "t", Instant.EPOCH)).first
        val draft = draftExperiment()
        val repository =
            InMemoryExperimentRepository().apply {
                seed(running)
                seed(draft)
            }

        shouldThrow<IllegalArgumentException> {
            handler(repository).handle(StartExperimentCommand(draft.experimentId, "user:owner", "trace-1"))
        }
    }

    @Test
    fun `存在しないExperimentIdはExperimentNotFoundExceptionを投げる`() {
        val repository = InMemoryExperimentRepository()

        shouldThrow<ExperimentNotFoundException> {
            handler(repository).handle(StartExperimentCommand(UUID.randomUUID(), "user:owner", "trace-1"))
        }
    }
}
