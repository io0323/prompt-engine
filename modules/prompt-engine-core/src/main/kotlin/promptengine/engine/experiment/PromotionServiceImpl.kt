package promptengine.engine.experiment

import promptengine.domain.experiment.PromotionService
import promptengine.domain.experiment.PromotionService.Companion.MIN_SAMPLE_SIZE
import promptengine.domain.experiment.PromotionService.Companion.SIGNIFICANCE_LEVEL
import promptengine.domain.experiment.StatisticalJudgment
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * [PromotionService]の実装（Welch's t-test、ADR-0034決定5）。
 *
 * 外部の統計ライブラリに依存せず、標準正規分布の累積分布関数（CDF）を
 * Abramowitz-Stegun近似で計算する。[MIN_SAMPLE_SIZE]（既定30）以上のサンプル数を
 * 要求する設計自体が「t分布は自由度が大きいほど標準正規分布に近づく」という近似の
 * 妥当性を裏付ける（自由度30以上でt分布と正規分布の差はごく小さい）ため、真のt分布
 * ではなく正規分布によるp値近似で十分と判断した。
 *
 * **限界（[PromotionService]のKDoc参照）**: この近似はLatency/Costのような右に歪んだ
 * 分布に対しては保守的に解釈されるべきである。多重検定補正・逐次検定は行わない。
 */
class PromotionServiceImpl : PromotionService {
    override fun judge(
        control: List<BigDecimal>,
        challenger: List<BigDecimal>,
    ): StatisticalJudgment {
        val sufficientSample = control.size >= MIN_SAMPLE_SIZE && challenger.size >= MIN_SAMPLE_SIZE
        val controlMean = mean(control)
        val challengerMean = mean(challenger)

        if (!sufficientSample) {
            return StatisticalJudgment(
                controlSampleSize = control.size,
                challengerSampleSize = challenger.size,
                controlMean = controlMean,
                challengerMean = challengerMean,
                pValue = null,
                sufficientSample = false,
                significant = false,
            )
        }

        val pValue = welchTTestPValue(control, challenger)
        return StatisticalJudgment(
            controlSampleSize = control.size,
            challengerSampleSize = challenger.size,
            controlMean = controlMean,
            challengerMean = challengerMean,
            pValue = pValue,
            sufficientSample = true,
            significant = pValue < SIGNIFICANCE_LEVEL,
        )
    }

    private fun welchTTestPValue(
        control: List<BigDecimal>,
        challenger: List<BigDecimal>,
    ): Double {
        val controlValues = control.map { it.toDouble() }
        val challengerValues = challenger.map { it.toDouble() }
        val controlMean = controlValues.average()
        val challengerMean = challengerValues.average()
        val controlVariance = sampleVariance(controlValues, controlMean)
        val challengerVariance = sampleVariance(challengerValues, challengerMean)

        val standardError = sqrt(controlVariance / controlValues.size + challengerVariance / challengerValues.size)
        if (standardError == 0.0) return if (controlMean == challengerMean) 1.0 else 0.0

        val tStatistic = (controlMean - challengerMean) / standardError
        // 両側検定: p = 2 * (1 - Φ(|t|))
        return 2.0 * (1.0 - standardNormalCdf(abs(tStatistic)))
    }

    private fun sampleVariance(
        values: List<Double>,
        mean: Double,
    ): Double {
        if (values.size < 2) return 0.0
        val sumSquaredDiff = values.sumOf { (it - mean) * (it - mean) }
        return sumSquaredDiff / (values.size - 1)
    }

    private fun mean(values: List<BigDecimal>): BigDecimal? {
        if (values.isEmpty()) return null
        val sum = values.reduce(BigDecimal::add)
        return sum.divide(BigDecimal(values.size), MathContext.DECIMAL64)
    }

    /**
     * 標準正規分布の累積分布関数。Abramowitz-Stegun近似（式26.2.17、最大誤差7.5e-8）を用いる。
     */
    private fun standardNormalCdf(x: Double): Double {
        val t = 1.0 / (1.0 + ABRAMOWITZ_STEGUN_P * x)
        val poly =
            t * (
                ABRAMOWITZ_STEGUN_B1 +
                    t * (
                        ABRAMOWITZ_STEGUN_B2 +
                            t * (ABRAMOWITZ_STEGUN_B3 + t * (ABRAMOWITZ_STEGUN_B4 + t * ABRAMOWITZ_STEGUN_B5))
                    )
            )
        val density = exp(-x * x / 2.0) / sqrt(2.0 * Math.PI)
        return 1.0 - density * poly
    }

    private companion object {
        const val ABRAMOWITZ_STEGUN_P = 0.2316419
        const val ABRAMOWITZ_STEGUN_B1 = 0.319381530
        const val ABRAMOWITZ_STEGUN_B2 = -0.356563782
        const val ABRAMOWITZ_STEGUN_B3 = 1.781477937
        const val ABRAMOWITZ_STEGUN_B4 = -1.821255978
        const val ABRAMOWITZ_STEGUN_B5 = 1.330274429
    }
}
