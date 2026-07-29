package promptengine.engine.compiler

import promptengine.domain.template.ast.PromptAst
import promptengine.engine.parser.PromptDslParserConfig
import promptengine.engine.parser.internal.BodyParser

/** `macros:` フロントマターの1エントリ（設計書§15.6）。宣言単位（Prompt/Template/Fragment）に閉じる（ADR-0010決定5）。 */
data class MacroDeclaration(
    val name: String,
    val params: List<String>,
    val body: List<PromptAst>,
)

/**
 * DSLフロントマターの生`macros`フィールド（設計書§15.6）を[MacroDeclaration]のリストへ
 * 変換する唯一の経路（[ImportsFieldMapper]と同型のパターン、ADR-0010決定5）。各エントリの
 * `body`（本文断片のDSLテキスト）は[BodyParser]（3aのパーサ内部実装、同一モジュール内で共有）で
 * 構文解析する。
 */
object MacrosFieldMapper {
    fun parse(rawMacros: Any?): List<MacroDeclaration> {
        if (rawMacros == null) return emptyList()
        require(rawMacros is List<*>) { "macros front matter field must be a list: $rawMacros" }
        return rawMacros.map(::parseEntry)
    }

    private fun parseEntry(raw: Any?): MacroDeclaration {
        require(raw is Map<*, *>) { "each macros entry must be a mapping: $raw" }
        val name = raw["name"] as? String
        val bodyText = raw["body"] as? String
        require(!name.isNullOrBlank()) { "macros entry missing 'name': $raw" }
        require(!bodyText.isNullOrBlank()) { "macros entry missing 'body': $raw" }

        val params = (raw["params"] as? List<*>)?.map { it as String } ?: emptyList()
        val maxNestingDepth = PromptDslParserConfig().maxNestingDepth
        val body = BodyParser(bodyText, 1, bodyText.lines(), maxNestingDepth).parse()
        return MacroDeclaration(name, params, body)
    }
}
