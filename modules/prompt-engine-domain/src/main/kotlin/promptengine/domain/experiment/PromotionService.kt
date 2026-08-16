package promptengine.domain.experiment

import java.math.BigDecimal

/**
 * Experiment勝者のPublish昇格に向けた統計判定（設計書§4.5、ADR-0034決定5）。
 *
 * 実装（`prompt-engine-core`の`PromotionServiceImpl`）はWelch's t-test（等分散を
 * 仮定しない2標本検定）を用いる。対象指標はLatency/TokenUsage/Costの3種
 * （設計書§2.12でM2実装済みの評価器のみ）。
 *
 * **限界（ADR-0034決定5に詳細）**: LatencyとCostは一般に右に大きく歪んだ分布になる
 * （M1実測でp99がp50の10倍以上）。平均の比較を前提とするt検定はこの形の分布では
 * 検出力が下がり外れ値の影響を受けやすいため、[StatisticalJudgment]は**参考値**として
 * 提示するに留め、自動昇格の判断根拠にしない（[Experiment.promote]は判定結果によらず
 * 呼び出せる）。多重検定補正・逐次検定（stopping rule）は未実装。他の検定手法へ差し替える
 * 場合は本Interfaceの実装を置き換える（設計書§16拡張ポイント#12と同じ拡張ポイント思想）。
 */
interface PromotionService {
    /**
     * [control]（既存/対照群）と[challenger]（比較対象群）の同一指標のスコア列を比較する。
     * いずれかのサンプル数が[MIN_SAMPLE_SIZE]未満の場合は[StatisticalJudgment.sufficientSample]
     * が`false`となり、[StatisticalJudgment.pValue]は`null`。
     */
    fun judge(
        control: List<BigDecimal>,
        challenger: List<BigDecimal>,
    ): StatisticalJudgment

    companion object {
        /** Variantあたりの最小サンプル数（正規近似が使える経験則的下限、ADR-0034決定5）。 */
        const val MIN_SAMPLE_SIZE = 30

        /** 有意水準。 */
        const val SIGNIFICANCE_LEVEL = 0.05
    }
}

/**
 * 2群比較の統計判定結果（ADR-0034決定5）。
 *
 * @param significant [pValue]が非nullかつ[PromotionService.SIGNIFICANCE_LEVEL]未満のときのみ`true`。
 */
data class StatisticalJudgment(
    val controlSampleSize: Int,
    val challengerSampleSize: Int,
    val controlMean: BigDecimal?,
    val challengerMean: BigDecimal?,
    val pValue: Double?,
    val sufficientSample: Boolean,
    val significant: Boolean,
)
