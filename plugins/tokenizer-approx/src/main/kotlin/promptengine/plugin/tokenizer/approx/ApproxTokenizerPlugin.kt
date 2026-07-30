package promptengine.plugin.tokenizer.approx

import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin
import kotlin.math.ceil

/**
 * [TokenizerPlugin]の正式な近似実装（設計書§16拡張ポイント#13、実装ガイド§6.7）。
 * `prompt-engine-core`のM1暫定実装（`NaiveTokenizerPlugin`、ADR-0012決定5）を置き換える。
 *
 * アルゴリズム: 文字種別ごとの近似カウント。
 * 主要な大規模言語モデルのBPE系トークナイザは、CJK文字（漢字・ひらがな・カタカナ・
 * ハングル等）を概ね1文字=1トークン、ASCII/ラテン文字を概ね4文字=1トークンとして
 * 消費する傾向があるため、この2区分の重み付け線形和で近似する。
 *
 * 1. 各コードポイントを、Unicodeブロックにより「CJK系」（[isCjk]、ひらがな・カタカナ・
 *    CJK統合漢字・CJK記号/句読点・ハングル音節・全角形）と「その他」（ASCII・ラテン文字・
 *    その他スクリプト・空白・記号）に分類する。
 * 2. CJK系の文字数を`weight 1.0`、その他の文字数を`weight 0.25`として合計し、
 *    小数点以下を切り上げる。
 *
 * 正確性より決定性（同一入力から常に同一の結果）と計算速度を優先する。ロケール依存の
 * 文字列処理（大文字小文字変換等）は行わないため、実行環境のデフォルトロケールに
 * 結果が左右されることはない。
 */
class ApproxTokenizerPlugin : TokenizerPlugin {
    override fun estimate(text: String): TokenCount {
        var cjkCount = 0
        var otherCount = 0
        text.codePoints().forEach { codePoint ->
            if (isCjk(codePoint)) cjkCount++ else otherCount++
        }
        val estimated = ceil(cjkCount * CJK_WEIGHT + otherCount * OTHER_WEIGHT).toInt()
        return TokenCount(estimated)
    }

    private fun isCjk(codePoint: Int): Boolean = CJK_RANGES.any { range -> codePoint in range }

    private companion object {
        const val CJK_WEIGHT = 1.0
        const val OTHER_WEIGHT = 0.25

        // CJK Symbols and Punctuation, Hiragana, Katakana, CJK Unified Ideographs,
        // Hangul Syllables, Halfwidth and Fullwidth Forms
        val CJK_RANGES =
            listOf(
                0x3000..0x303F,
                0x3040..0x309F,
                0x30A0..0x30FF,
                0x4E00..0x9FFF,
                0xAC00..0xD7A3,
                0xFF00..0xFFEF,
            )
    }
}
