package promptengine.domain.tokenizer

import promptengine.domain.shared.TokenCount

/**
 * テキストの推定Token数を計算する拡張ポイント（設計書§16拡張ポイント#13）。
 *
 * §3.4疑似コードには定義が無かったため、他のEngine系Interface
 * （[promptengine.domain.composition.CompositionService]等）と同型でADR-0012にて新設した。
 * Interfaceはdomainに置き、M1暫定実装（文字数からの近似計算）は`prompt-engine-core`、
 * 正式な実装（`plugins/tokenizer-approx`、文字種別の近似カウント）はP6（実装ガイド§6.7）で
 * 追加する。
 */
fun interface TokenizerPlugin {
    /** [text]の推定Token数を返す。厳密さより決定性・速度を優先した近似値でよい。 */
    fun estimate(text: String): TokenCount
}
