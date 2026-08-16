package promptengine.application.query

import promptengine.domain.evaluation.EvaluationRepository
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.experiment.PromotionService
import promptengine.domain.experiment.Variant
import java.math.BigDecimal
import java.util.UUID

/** 対象指標（設計書§2.12でM2実装済みの評価器のみ、ADR-0034決定5）。 */
private val JUDGED_METRIC_TYPES = listOf("Latency", "TokenUsage", "Cost")

/** `GET /experiments/{id}/results`の1 Variant分のスコア要約。 */
data class VariantResultSummary(val variantName: String, val weightPct: Int, val isControl: Boolean)

/**
 * 1指標・1Variant（非control）分の統計判定。`prompt-engine-interface`が
 * `promptengine.domain..`に依存できない（ArchUnitルール）ため、
 * `promptengine.domain.experiment.StatisticalJudgment`を埋め込まずフィールドを展開する。
 */
data class MetricJudgmentSummary(
    val metricType: String,
    val challengerVariantName: String,
    val controlSampleSize: Int,
    val challengerSampleSize: Int,
    val controlMean: BigDecimal?,
    val challengerMean: BigDecimal?,
    val pValue: Double?,
    val sufficientSample: Boolean,
    val significant: Boolean,
)

data class ExperimentResultsQuery(val experimentId: UUID)

data class ExperimentResultsView(
    val experimentId: UUID,
    val promptKey: String,
    val status: String,
    val controlVariantName: String,
    val variants: List<VariantResultSummary>,
    val judgments: List<MetricJudgmentSummary>,
)

/**
 * `GET /experiments/{id}/results`（設計書§13.1、ADR-0034決定5）。
 *
 * `control`はVariant名`"control"`があればそれを、無ければ名前昇順で最初のVariantを使う
 * （[Experiment.variants]は永続化層から名前昇順で読み込まれるため、明示的な作成順を
 * DBが保持しない。M2スコープでの単純化として本KDocに明記する）。
 *
 * 各非control Variantについて、[JUDGED_METRIC_TYPES]の指標ごとに[promotionService]で
 * controlとの統計判定を行う。判定結果は参考値であり、自動昇格には使わない
 * （[PromotionService]のKDoc参照）。
 */
class GetExperimentResultsHandler(
    private val experimentRepository: ExperimentRepository,
    private val evaluationRepository: EvaluationRepository,
    private val promotionService: PromotionService,
) {
    fun handle(query: ExperimentResultsQuery): ExperimentResultsView {
        val experiment =
            experimentRepository.findById(query.experimentId)
                ?: throw ExperimentNotFoundException(query.experimentId)

        val control = controlVariant(experiment.variants)
        val challengers = experiment.variants.filter { it.variantId != control.variantId }

        val judgments =
            challengers.flatMap { challenger ->
                JUDGED_METRIC_TYPES.map { metricType ->
                    val controlScores = evaluationRepository.findScoresByVariant(control.variantId, metricType)
                    val challengerScores = evaluationRepository.findScoresByVariant(challenger.variantId, metricType)
                    val judgment = promotionService.judge(controlScores, challengerScores)
                    MetricJudgmentSummary(
                        metricType = metricType,
                        challengerVariantName = challenger.name,
                        controlSampleSize = judgment.controlSampleSize,
                        challengerSampleSize = judgment.challengerSampleSize,
                        controlMean = judgment.controlMean,
                        challengerMean = judgment.challengerMean,
                        pValue = judgment.pValue,
                        sufficientSample = judgment.sufficientSample,
                        significant = judgment.significant,
                    )
                }
            }

        return ExperimentResultsView(
            experimentId = experiment.experimentId,
            promptKey = experiment.promptKey.value,
            status = experiment.status::class.simpleName ?: "Unknown",
            controlVariantName = control.name,
            variants =
                experiment.variants.map {
                    VariantResultSummary(it.name, it.weightPct, it.variantId == control.variantId)
                },
            judgments = judgments,
        )
    }

    private fun controlVariant(variants: List<Variant>): Variant =
        variants.find { it.name == "control" } ?: variants.minBy { it.name }
}
