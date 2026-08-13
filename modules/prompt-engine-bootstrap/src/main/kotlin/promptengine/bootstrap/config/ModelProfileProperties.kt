package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * [promptengine.domain.optimization.ModelProfile]の供給元（`promptengine.model-profile.*`、
 * M2-1c、ADR-0030決定1）。
 *
 * APAPが存在しないため、モデルのメタデータ（コンテキスト長・単価）を実行時に問い合わせる先が無い。
 * `ModelProfile`は設定値として保持し、価格改定・モデル追加のたびに値を変更してデプロイし直す
 * 運用とする。設計書§2.12で確認済みの通り、`ModelProfile.costPerToken`は実行時点の値を
 * `PromptExecutedEvent`へ焼き込む方式が既に確定しており（`CostEvaluationRule`は
 * イベントに載った値のみを使い、実行のたびに`ModelProfile`を引き直さない）、
 * 「静的設定を都度読む」という本方針は既存の実装と矛盾しない。APAP実装後にこの供給元を
 * config直読みからAPAP経由の動的取得へ移行する（既存の値の使われ方自体は変えない）。
 *
 * 設定値が実際のモデルの上限・単価と乖離した場合、[maxContextTokens]が実際より大きいと
 * `OptimizationEngine`のbudget判定が緩すぎてプロバイダ側でコンテキスト長超過エラーになる
 * （実行時に検知可能。暫定Providerアダプタ実装がこの種のエラーを他の4xxと区別可能な
 * 原因情報付きで報告する、ADR-0030決定1）。[costPerToken]の乖離はコスト集計の誤りとして
 * 現れるが、自動検知の仕組みは今回設けない（継続的な価格差分監視は本フェーズのスコープを
 * 超える、ADR-0030）。
 */
@ConfigurationProperties(prefix = "promptengine.model-profile")
data class ModelProfileProperties(
    /** モデルのコンテキスト長上限（トークン数）。実際のモデルの上限以下に保つこと。 */
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    /** [promptengine.domain.tokenizer.TokenizerPlugin]解決用の不透明な識別子。 */
    val tokenizerId: String = DEFAULT_TOKENIZER_ID,
    /** 入力・出力を区別しない単一のブレンド単価（設計書§2.12、既知の簡略化）。 */
    val costPerToken: String = DEFAULT_COST_PER_TOKEN,
) {
    init {
        require(maxContextTokens > 0) {
            "promptengine.model-profile.max-context-tokens must be positive: $maxContextTokens"
        }
        require(tokenizerId.isNotBlank()) { "promptengine.model-profile.tokenizer-id must not be blank" }
        require(runCatching { costPerToken.toBigDecimal() }.isSuccess) {
            "promptengine.model-profile.cost-per-token must be a valid decimal: $costPerToken"
        }
    }

    companion object {
        private const val DEFAULT_MAX_CONTEXT_TOKENS = 8_000
        private const val DEFAULT_TOKENIZER_ID = "approx"
        private const val DEFAULT_COST_PER_TOKEN = "0.000001"
    }
}
