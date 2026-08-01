package promptengine.domain.render

/**
 * DSL `output:`宣言（設計書§15.1）の解決結果（ADR-0015決定9）。
 *
 * [PromptVersion][promptengine.domain.prompt.PromptVersion]/
 * [CompiledPrompt][promptengine.domain.composition.CompiledPrompt]が
 * `validation: ValidationSettings`（ADR-0012）と同じ配線パターンで保持する。
 * Template/Fragmentの`output`とはマージせず、Prompt自身の宣言のみが有効
 * （`validation`と同じ扱い）。
 *
 * `schemaRef`から実際の`OutputSchema`（ADR-0014）を解決する経路は未設計であり
 * [Issue #36](https://github.com/io0323/prompt-engine/issues/36)で追跡する。
 */
data class OutputDeclaration(
    val format: OutputFormat,
    val schemaRef: String? = null,
)
