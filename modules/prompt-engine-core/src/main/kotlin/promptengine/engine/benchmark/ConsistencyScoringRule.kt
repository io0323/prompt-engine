package promptengine.engine.benchmark

import promptengine.domain.benchmark.BenchmarkScoringRule
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Consistencyの既定実装（設計書§16-15、ADR-0035決定5）。
 *
 * [expectedOutput]（期待出力）は使わない。同一入力のN回実行結果同士を比較するだけで成立する
 * 指標であるため（Accuracyの正規化完全一致という思想を、「期待出力」ではなく「他の実行結果
 * 同士」に適用する）。
 *
 * [actualOutputs]を[normalizeForComparison]（[NormalizedExactMatchScoringRule]と共有する
 * 正規化規則）した上で、最頻出の正規化済み文字列と一致する件数の割合をスコアとする
 * （0.0〜1.0、小数点以下4桁）。同数の最頻値が複数存在する場合は、正規化済み文字列の
 * 自然順序で最小のものを採用する（実行順や`Map`の反復順に依存させないため）。
 * [actualOutputs]が空の場合は算出不能として`null`を返す。
 *
 * 埋め込み類似度は差替例（設計書§16-15）であり本実装のスコープ外。
 */
class ConsistencyScoringRule : BenchmarkScoringRule {
    override val metricType: String = METRIC_TYPE

    override fun score(
        actualOutputs: List<String>,
        expectedOutput: String?,
    ): BigDecimal? {
        if (actualOutputs.isEmpty()) return null
        val normalized = actualOutputs.map { normalizeForComparison(it) }
        val mostFrequent =
            normalized.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first()
        return BigDecimal(mostFrequent.value).divide(BigDecimal(normalized.size), SCALE, RoundingMode.HALF_UP)
    }

    companion object {
        const val METRIC_TYPE = "Consistency"
        private const val SCALE = 4
    }
}
