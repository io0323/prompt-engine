package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.Variant
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

class UpdateExperimentTrafficHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private fun variant(
        name: String,
        weightPct: Int,
    ) = Variant(UUID.randomUUID(), name, SemVer(1, 0, 0), weightPct)

    private fun runningExperiment(): Experiment {
        val draft =
            Experiment.create(
                promptKey,
                ExperimentType.CANARY,
                listOf(variant("control", 90), variant("treatment", 10)),
                TrafficPolicy(),
            )
        return draft.start(EventContext("owner", "t", Instant.EPOCH)).first
    }

    private fun handler(repository: InMemoryExperimentRepository) =
        UpdateExperimentTrafficHandler(repository, PassthroughIdempotentCommandExecutor())

    @Test
    fun `Variant名を維持したまま重みを更新する`() {
        val experiment = runningExperiment()
        val repository = InMemoryExperimentRepository().apply { seed(experiment) }
        val command =
            UpdateExperimentTrafficCommand(
                experiment.experimentId,
                listOf(VariantWeightInput("control", 30), VariantWeightInput("treatment", 70)),
                "user:owner",
                "trace-1",
            )

        handler(repository).handle(command)

        val updated = repository.findById(experiment.experimentId)!!
        updated.variants.single { it.name == "control" }.weightPct shouldBe 30
        updated.variants.single { it.name == "treatment" }.weightPct shouldBe 70
    }

    @Test
    fun `存在しないVariant名を含む更新はIllegalArgumentExceptionを投げる`() {
        val experiment = runningExperiment()
        val repository = InMemoryExperimentRepository().apply { seed(experiment) }
        val command =
            UpdateExperimentTrafficCommand(
                experiment.experimentId,
                listOf(VariantWeightInput("control", 30), VariantWeightInput("unknown", 70)),
                "user:owner",
                "trace-1",
            )

        shouldThrow<IllegalArgumentException> { handler(repository).handle(command) }
    }

    @Test
    fun `存在しないExperimentIdはExperimentNotFoundExceptionを投げる`() {
        val repository = InMemoryExperimentRepository()

        shouldThrow<ExperimentNotFoundException> {
            handler(
                repository,
            ).handle(UpdateExperimentTrafficCommand(UUID.randomUUID(), emptyList(), "user:owner", "trace-1"))
        }
    }
}
