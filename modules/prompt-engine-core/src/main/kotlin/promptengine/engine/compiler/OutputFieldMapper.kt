package promptengine.engine.compiler

import promptengine.domain.render.OutputDeclaration
import promptengine.domain.render.OutputFormat

/**
 * DSLフロントマターの生`output`フィールド（設計書§15.1）を[OutputDeclaration]へ
 * 変換する唯一の経路（[ValidationFieldMapper]と同型のパターン、ADR-0015決定9）。
 *
 * `validation`同様、この変換はDSL取り込み（authoring/ingestion）時の関心事であり、
 * [CompositionServiceImpl]は呼び出さない。`PromptVersion.output`は、ingestion時に
 * 既にこのMapperで確定させた構造化データとして扱う。
 */
object OutputFieldMapper {
    /**
     * [rawOutput]が`null`なら`output:`ブロック自体が宣言されていないため`null`を返す。
     *
     * @throws IllegalArgumentException [rawOutput]がマッピングでない、`format`が
     *   欠落または`json`/`xml`/`markdown`/`text`のいずれでもない、`schemaRef`が
     *   文字列でない場合。
     */
    fun parse(rawOutput: Any?): OutputDeclaration? {
        if (rawOutput == null) return null
        require(rawOutput is Map<*, *>) { "output front matter field must be a mapping: $rawOutput" }

        val format = parseFormat(rawOutput["format"])
        val schemaRef = parseSchemaRef(rawOutput["schemaRef"])

        return OutputDeclaration(format, schemaRef)
    }

    private fun parseFormat(raw: Any?): OutputFormat {
        require(raw != null) { "output.format must be present" }
        require(raw is String) { "output.format must be a string: $raw" }
        return when (raw) {
            "json" -> OutputFormat.JSON
            "xml" -> OutputFormat.XML
            "markdown" -> OutputFormat.MARKDOWN
            "text" -> OutputFormat.TEXT
            else -> throw IllegalArgumentException("output.format must be one of json/xml/markdown/text: $raw")
        }
    }

    private fun parseSchemaRef(raw: Any?): String? {
        if (raw == null) return null
        require(raw is String) { "output.schemaRef must be a string: $raw" }
        return raw
    }
}
