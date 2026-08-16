package promptengine.application.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.InMemoryExperimentRepository
import promptengine.domain.evaluation.EvaluationRepository
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.experiment.PromotionService
import promptengine.domain.experiment.StatisticalJudgment
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.Variant
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.util.UUID

class GetExperimentResultsHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private fun variant(
        name: String,
        weightPct: Int,
    ) = Variant(UUID.randomUUID(), name, SemVer(1, 0, 0), weightPct)

    private class FixedEvaluationRepository : EvaluationRepository {
        override fun saveAll(records: List<promptengine.domain.evaluation.EvaluationRecord>): Int = records.size

        override fun findScoresByVariant(
            variantId: UUID,
            metricType: String,
        ): List<BigDecimal> = listOf(BigDecimal(100))
    }

    private class FixedPromotionService : PromotionService {
        override fun judge(
            control: List<BigDecimal>,
            challenger: List<BigDecimal>,
        ): StatisticalJudgment =
            StatisticalJudgment(
                controlSampleSize = control.size,
                challengerSampleSize = challenger.size,
                controlMean = control.firstOrNull(),
                challengerMean = challenger.firstOrNull(),
                pValue = 0.5,
                sufficientSample = true,
                significant = false,
            )
    }

    private fun handler(experimentRepository: InMemoryExperimentRepository) =
        GetExperimentResultsHandler(experimentRepository, FixedEvaluationRepository(), FixedPromotionService())

    @Test
    fun `control という名前のVariantがあればそれをcontrolとして扱う`() {
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("treatment", 50), variant("control", 50)),
                TrafficPolicy(),
            )
        val repository = InMemoryExperimentRepository().apply { seed(experiment) }

        val view = handler(repository).handle(ExperimentResultsQuery(experiment.experimentId))

        view.controlVariantName shouldBe "control"
        view.judgments.map { it.challengerVariantName } shouldBe listOf("treatment", "treatment", "treatment")
    }

    @Test
    fun `control という名前が無ければ名前昇順で最初のVariantをcontrolとして扱う`() {
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("zeta", 50), variant("alpha", 50)),
                TrafficPolicy(),
            )
        val repository = InMemoryExperimentRepository().apply { seed(experiment) }

        val view = handler(repository).handle(ExperimentResultsQuery(experiment.experimentId))

        view.controlVariantName shouldBe "alpha"
    }

    @Test
    fun `対象指標はLatency TokenUsage Costの3種`() {
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 50), variant("treatment", 50)),
                TrafficPolicy(),
            )
        val repository = InMemoryExperimentRepository().apply { seed(experiment) }

        val view = handler(repository).handle(ExperimentResultsQuery(experiment.experimentId))

        view.judgments.map { it.metricType }.toSet() shouldBe setOf("Latency", "TokenUsage", "Cost")
    }

    @Test
    fun `存在しないExperimentIdはExperimentNotFoundExceptionを投げる`() {
        val repository = InMemoryExperimentRepository()

        shouldThrow<ExperimentNotFoundException> {
            handler(
                repository,
            ).handle(ExperimentResultsQuery(UUID.randomUUID()))
        }
    }
}
