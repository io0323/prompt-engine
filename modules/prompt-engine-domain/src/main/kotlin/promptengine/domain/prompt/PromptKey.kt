package promptengine.domain.prompt

/**
 * Promptの識別子。`namespace/name`（ちょうど2セグメント）形式（設計書§4.4）。
 *
 * 3セグメント以上は許容しない（ADR-0023参照）: REST API（`prompt-engine-interface`）は
 * `/prompts/{namespace}/{name}/...`という2つの独立したパス変数でこの値を受け取り、
 * Spring MVCの`PathPatternParser`はパス変数1つがセグメント境界（`/`）をまたぐマッチングを
 * サポートしないため、3セグメント以上の`namespace/mid/name`のような値はそもそもHTTP経由で
 * 表現できない。ドメインの制約をAPI経由で表現可能な範囲に一致させる。
 */
data class PromptKey(val value: String) {
    init {
        require(PATTERN.matches(value)) { "invalid PromptKey format: $value" }
    }

    val namespace: String get() = value.substringBefore('/')
    val name: String get() = value.substringAfter('/')

    private companion object {
        val PATTERN = Regex("[a-z0-9-]+/[a-z0-9-]+")
    }
}
