package promptengine.domain.render

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.variable.BindingSet

/**
 * Renderingの入口（設計書§2.6ステージ8・§5.7シーケンス）。
 *
 * §3.4疑似コードには定義が無く、[promptengine.domain.validation.ValidationEngine]と同型で
 * ADR-0013にて新設した。実装（`RenderEngineImpl`）は`prompt-engine-core`に置き、
 * [TemplateEngine]経由でのみASTを展開する。
 *
 * `OutputFormatter.instruction()`によるフォーマット指示文の`messages`への注入は、P7で
 * `RenderEngineImpl`に接続した（既存system messageへの追記／無ければ新規system message追加、
 * その後renderHash算出、ADR-0014決定5）。[outputSchema]はM1では`CompiledPrompt`から自動導出
 * されず、呼出側が明示的に渡す値である（[Issue #32](https://github.com/io0323/prompt-engine/issues/32)）。
 */
interface RenderEngine {
    /**
     * [compiled]を[variableBindings]・[contextBindings]で束縛して[RenderedPrompt]へ展開する。
     *
     * 同一の([compiled], [variableBindings], [contextBindings], [outputFormat], [outputSchema],
     * EngineVersion)からは常にバイト同一の[RenderedPrompt.renderHash]を返す
     * （設計書§2.9、ADR-0013決定1）。[modelHints]は結果の[RenderedPrompt.modelHints]へ
     * そのまま渡るのみで、renderHashの算出には一切関与しない（ADR-0035決定5）。
     */
    @Suppress("LongParameterList")
    fun render(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        outputFormat: OutputFormat,
        outputSchema: OutputSchema? = null,
        modelHints: ModelHints? = null,
    ): RenderedPrompt
}
