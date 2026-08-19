package promptengine.engine.benchmark

import promptengine.domain.benchmark.BenchmarkScoringRule
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Determinismの既定実装（設計書§16-15、ADR-0035決定5）。
 *
 * [expectedOutput]は使わない。[actualOutputs]の最初の出力とバイト完全一致（正規化なし）する
 * 件数の割合をスコアとする（0.0〜1.0、小数点以下4桁）。Accuracy/Consistencyと異なり
 * 正規化しないのは、「表記ゆれを無視した一致率」ではなく「`temperature=0`で本当に
 * バイト同一の出力が返るか」自体を検証する指標であるため（意味を持つのは`temperature=0`の
 * ときのみ。Benchmarkワーカーは本指標を要求するTargetのN回実行を強制的に`temperature=0`で
 * 行う、`Benchmark.create`のバリデーション参照）。
 *
 * [actualOutputs]が空の場合は算出不能として`null`を返す。
 */
class DeterminismScoringRule : BenchmarkScoringRule {
    override val metricType: String = METRIC_TYPE

    override fun score(
        actualOutputs: List<String>,
        expectedOutput: String?,
    ): BigDecimal? {
        if (actualOutputs.isEmpty()) return null
        val first = actualOutputs.first()
        val matchCount = actualOutputs.count { it == first }
        return BigDecimal(matchCount).divide(BigDecimal(actualOutputs.size), SCALE, RoundingMode.HALF_UP)
    }

    companion object {
        const val METRIC_TYPE = "Determinism"
        private const val SCALE = 4
    }
}
