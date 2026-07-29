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
     *   数値項目が数値でない、`placeholders`が`strict`/`lenient`以外である場合。
     */
    fun parse(rawValidation: Any?): ValidationSettings {
        if (rawValidation == null) return ValidationSettings()
        require(rawValidation is Map<*, *>) { "validation front matter field must be a mapping: $rawValidation" }

        val maxLength = (rawValidation["maxLength"] as? Number)?.toInt()
        val maxTokens = (rawValidation["maxTokens"] as? Number)?.toInt()
        val policies = (rawValidation["policies"] as? List<*>)?.map { it as String } ?: emptyList()
        val placeholders = parsePlaceholderMode(rawValidation["placeholders"])

        return ValidationSettings(maxLength, maxTokens, policies, placeholders)
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
