package promptengine.domain.render

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.variable.BindingSet

/**
 * Renderingの入口（設計書§2.6ステージ8・§5.7シーケンス）。
 *
 * §3.4疑似コードには定義が無く、[promptengine.domain.validation.ValidationEngine]と同型で
 * ADR-0013にて新設した。実装（`RenderEngineImpl`）は`prompt-engine-core`に置き、
 * [TemplateEngine]経由でのみASTを展開する。
 *
 * `OutputFormatter.instruction()`によるフォーマット指示文の注入はP7スコープのため、
 * M1の実装は[outputFormat]を[RenderedPrompt]へ素通しするのみで、指示文生成は行わない
 * （ADR-0013決定10）。
 */
interface RenderEngine {
    fun render(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        outputFormat: OutputFormat,
    ): RenderedPrompt
}
