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
    /**
     * [compiled]を[variableBindings]・[contextBindings]で束縛して[RenderedPrompt]へ展開する。
     *
     * 同一の([compiled], [variableBindings], [contextBindings], [outputFormat],
     * EngineVersion)からは常にバイト同一の[RenderedPrompt.renderHash]を返す
     * （設計書§2.9、ADR-0013決定1）。[outputFormat]はM1では[RenderedPrompt]へ素通しするのみで、
     * `OutputFormatter.instruction()`によるフォーマット指示文の注入は行わない（P7スコープ、
     * ADR-0013決定10）。
     */
    fun render(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        outputFormat: OutputFormat,
    ): RenderedPrompt
}
