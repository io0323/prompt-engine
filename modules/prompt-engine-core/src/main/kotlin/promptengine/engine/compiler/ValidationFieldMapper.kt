package promptengine.engine.compiler

import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.ValidationSettings

/**
 * DSLフロントマターの生`validation`フィールド（設計書§15.7）を[ValidationSettings]へ
 * 変換する唯一の経路（[ImportsFieldMapper]/[MacrosFieldMapper]と同型のパターン）。
 *
 * `variables`/`context`同様、この変換はDSL取り込み（authoring/ingestion）時の関心事であり、
 * [CompositionServiceImpl]は呼び出さない。`PromptVersion.validation`は、ingestion時に
 * 既にこのMapperで確定させた構造化データとして扱う（`PromptVersion.variables`/
 * `contextRequirements`が`CompositionServiceImpl`によってDSLテキストから毎回
 * 再導出されるわけではないのと同じ前例、ADR-0012決定2）。
 */
object ValidationFieldMapper {
    /**
     * [rawValidation]が`null`なら全項目既定値の[ValidationSettings]を返す。
     *
     * @throws IllegalArgumentException [rawValidation]がマッピングでない、
     *   `maxLength`/`maxTokens`が整数でない（小数・文字列等）、`policies`の要素が
     *   文字列でない、`placeholders`が`strict`/`lenient`以外である場合。
     */
    fun parse(rawValidation: Any?): ValidationSettings {
        if (rawValidation == null) return ValidationSettings()
        require(rawValidation is Map<*, *>) { "validation front matter field must be a mapping: $rawValidation" }

        val maxLength = parseLimit(rawValidation, "maxLength")
        val maxTokens = parseLimit(rawValidation, "maxTokens")
        val policies = parsePolicies(rawValidation["policies"])
        val placeholders = parsePlaceholderMode(rawValidation["placeholders"])

        return ValidationSettings(maxLength, maxTokens, policies, placeholders)
    }

    /** 数値かつ整数でなければ[IllegalArgumentException]を投げる（小数・文字列を無音でnull化しない）。 */
    private fun parseLimit(
        rawValidation: Map<*, *>,
        field: String,
    ): Int? {
        val raw = rawValidation[field] ?: return null
        require(raw is Number) { "validation.$field must be a number: $raw" }
        val value = raw.toLong()
        require(raw.toDouble() == value.toDouble() && value in Int.MIN_VALUE..Int.MAX_VALUE) {
            "validation.$field must be an integer: $raw"
        }
        return value.toInt()
    }

    private fun parsePolicies(raw: Any?): List<String> {
        if (raw == null) return emptyList()
        require(raw is List<*>) { "validation.policies must be a list: $raw" }
        return raw.map {
            require(it is String) { "validation.policies entries must be strings: $it" }
            it
        }
    }

    private fun parsePlaceholderMode(raw: Any?): PlaceholderMode {
        if (raw == null) return PlaceholderMode.LENIENT
        require(raw is String) { "validation.placeholders must be a string: $raw" }
        return when (raw) {
            "strict" -> PlaceholderMode.STRICT
            "lenient" -> PlaceholderMode.LENIENT
            else -> throw IllegalArgumentException("validation.placeholders must be 'strict' or 'lenient': $raw")
        }
    }
}
