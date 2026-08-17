package promptengine.engine.benchmark

import promptengine.domain.benchmark.BenchmarkScoringRule
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

/**
 * Accuracyの既定実装（正規化完全一致、設計書§16-15、ADR-0035決定4）。
 *
 * 正規化規則（[normalize]、この順で適用する）:
 * 1. Unicode正規化形**NFC**を適用する（合成済み文字と分解済み文字の表記ゆれを吸収する。
 *    [promptengine.engine.render.RenderHashCalculator]が採用しているのと同じ形で、
 *    このリポジトリ内での正規化方針を統一する）。
 * 2. 前後の空白（`String.trim()`。半角空白・タブ・改行を含む）を除去する。
 * 3. **`Locale.ROOT`**で大文字小文字を無視する（`String.lowercase(Locale.ROOT)`）。
 *    実行環境のデフォルトロケールに結果が左右されないようにするため常に`ROOT`を使う
 *    （例: トルコ語ロケールでは`"I".lowercase()`が`"i"`ではなく`"ı"`になり、既定ロケール
 *    依存だと環境によって一致判定が変わってしまう）。
 *
 * [actualOutputs]（N回実行の結果）のうち、正規化後に[expectedOutput]と一致する件数の
 * 割合をスコアとする（0.0〜1.0、小数点以下4桁）。[expectedOutput]が`null`、または
 * [actualOutputs]が空の場合は算出不能として`null`を返す（`EvaluationRule.evaluate`と
 * 同じ「nullはスコア0ではなく算出不能」の規約）。
 *
 * 構造的一致（JSONキー単位）・意味的類似（埋め込み距離）は差替例（設計書§16-15）であり
 * 本実装のスコープ外。
 */
class NormalizedExactMatchScoringRule : BenchmarkScoringRule {
    override val metricType: String = METRIC_TYPE

    override fun score(
        actualOutputs: List<String>,
        expectedOutput: String?,
    ): BigDecimal? {
        if (expectedOutput == null || actualOutputs.isEmpty()) return null
        val normalizedExpected = normalize(expectedOutput)
        val matchCount = actualOutputs.count { normalize(it) == normalizedExpected }
        return BigDecimal(matchCount).divide(BigDecimal(actualOutputs.size), SCALE, RoundingMode.HALF_UP)
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).trim().lowercase(Locale.ROOT)

    companion object {
        const val METRIC_TYPE = "Accuracy"
        private const val SCALE = 4
    }
}
