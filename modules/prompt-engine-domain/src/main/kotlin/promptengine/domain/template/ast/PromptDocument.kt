package promptengine.domain.template.ast

/**
 * Front Matter（YAML）の生の構造化表現（設計書§15.1）。
 *
 * `pe`/`kind`/`key`/`variables`/`context`/`output`/`validation`/`macros` 等の
 * フィールドをドメイン型（[promptengine.domain.variable.VariableDefinition] 等）へ
 * マッピングする責務はTemplate/Fragment Aggregate（3b）が持つ。3aのParserは
 * 「安全にYAMLとして構文解析できること」のみを保証し、意味付けは行わない。
 */
data class PromptFrontMatter(val fields: Map<String, Any?>)

/** Parserの出力全体。Front Matterと本文ASTの組。 */
data class PromptDocument(
    val frontMatter: PromptFrontMatter,
    val body: List<PromptAst>,
)
