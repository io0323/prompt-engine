package promptengine.engine.validation

import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin
import kotlin.math.ceil

/**
 * M1暫定の[TokenizerPlugin]実装（ADR-0012決定5）。**恒久的な実装ではない**。
 *
 * アルゴリズム: 文字数を[CHARS_PER_TOKEN]で割って切り上げる、決定性のみを優先した
 * 近似実装。文字種別（CJK/ASCII等）は一切区別しない。正式な近似実装
 * （`plugins/tokenizer-approx`、文字種別の近似カウント）はP6（実装ガイド§6.7）で
 * 追加され、本クラスを置き換える。
 *
 * 呼出側（[LengthValidationRule]）は[TokenizerPlugin]インターフェースのみに依存し、
 * このクラス自体を直接参照しない（コンストラクタ注入）。P6で`plugins/tokenizer-approx`の
 * 実装に差し替える際、`LengthValidationRule`側の実装変更は不要で、DI結線
 * （`prompt-engine-bootstrap`のConfigurationクラス）を差し替えるだけでよい。
 */
class NaiveTokenizerPlugin : TokenizerPlugin {
    override fun estimate(text: String): TokenCount = TokenCount(ceil(text.length / CHARS_PER_TOKEN).toInt())

    private companion object {
        const val CHARS_PER_TOKEN = 4.0
    }
}
