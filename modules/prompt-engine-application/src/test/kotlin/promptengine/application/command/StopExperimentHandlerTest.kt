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
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

class StopExperimentHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private fun variant(
        name: String,
        weightPct: Int,
    ) = Variant(UUID.randomUUID(), name, SemVer(1, 0, 0), weightPct)

    private fun runningExperiment(): Experiment {
        val draft =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 50), variant("treatment", 50)),
                TrafficPolicy(),
            )
        return draft.start(EventContext("owner", "t", Instant.EPOCH)).first
    }

    private fun handler(repository: InMemoryExperimentRepository) =
        StopExperimentHandler(repository, PassthroughIdempotentCommandExecutor())

    @Test
    fun `RunningのExperimentをStoppedへ遷移させる`() {
        val experiment = runningExperiment()
        val repository = InMemoryExperimentRepository().apply { seed(experiment) }

        val result = handler(repository).handle(StopExperimentCommand(experiment.experimentId, "user:owner", "trace-1"))

        repository.findById(result.experimentId)!!.status shouldBe ExperimentStatus.Stopped
        repository.savedEvents.map { it.eventType } shouldBe listOf("ExperimentStopped")
    }

    @Test
    fun `Draft状態からの停止はInvalidStateTransitionExceptionを投げる`() {
        val draft =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 50), variant("treatment", 50)),
                TrafficPolicy(),
            )
        val repository = InMemoryExperimentRepository().apply { seed(draft) }

        shouldThrow<InvalidStateTransitionException> {
            handler(repository).handle(StopExperimentCommand(draft.experimentId, "user:owner", "trace-1"))
        }
    }

    @Test
    fun `存在しないExperimentIdはExperimentNotFoundExceptionを投げる`() {
        val repository = InMemoryExperimentRepository()

        shouldThrow<ExperimentNotFoundException> {
            handler(repository).handle(StopExperimentCommand(UUID.randomUUID(), "user:owner", "trace-1"))
        }
    }
}
