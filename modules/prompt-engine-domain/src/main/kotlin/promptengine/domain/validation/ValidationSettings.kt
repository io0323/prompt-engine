package promptengine.domain.validation

/**
 * DSL `validation:`フロントマターの解決結果（設計書§15.7、ADR-0012決定2）。
 *
 * `PromptVersion`/`CompiledPrompt`が保持する（`contextRequirements`と同様、
 * Template/Fragmentの`validation`とはマージしない。Prompt自身の宣言のみ有効）。
 * 全フィールド省略時の既定は「無制限・lenient」（既存Promptの挙動を変えない
 * 後方互換な既定値。`STRICT`をデフォルトにしない）。
 *
 * [maxLength] は文字数上限（`LengthValidationRule`）、[maxTokens] は推定Token数上限
 * （同）、[policies] は適用する`PolicyValidationRule`のID一覧（Rule自身が
 * `id() in policies`で自己判定する）、[placeholders] は`PlaceholderValidationRule`の
 * strict/lenient切替。
 */
data class ValidationSettings(
    val maxLength: Int? = null,
    val maxTokens: Int? = null,
    val policies: List<String> = emptyList(),
    val placeholders: PlaceholderMode = PlaceholderMode.LENIENT,
) {
    init {
        require(maxLength == null || maxLength > 0) { "maxLength must be positive: $maxLength" }
        require(maxTokens == null || maxTokens > 0) { "maxTokens must be positive: $maxTokens" }
    }
}
