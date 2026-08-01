package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderEngine

/**
 * Stage 8（Rendering、設計書§2.6）。[RenderEngine]へ委譲する。
 *
 * 実効`outputFormat`の優先順位（ADR-0015決定9）: 呼出パラメータ（`request.outputFormat`）が
 * 明示指定された値 ?: `CompiledPrompt.output?.format` ?: [OutputFormat.TEXT]（既定）。
 * `outputSchema`は`request.outputSchema`（呼出側が明示的に渡す値）のみを使う
 * （`schemaRef`からの自動解決は未設計、Issue #36）。
 *
 * `variableBindings`/`contextBindings`が`null`（Stage 4・5未実行）の場合は空既定へ
 * フォールバックせず`checkNotNull`で拒否する（他の8ステージと同じfail-fast方針、
 * ADR-0015決定4修正）。空既定へ黙って倒すと、未束縛のまま描画が「成功」してしまい、
 * 変数・Contextが本来展開されるべき箇所が空文字のまま欠落した壊れた出力が、
 * エラーにならず正常系として下流（Execution・呼出元）へ流れてしまう。
 */
class RenderingStage(private val renderEngine: RenderEngine) : PipelineStage {
    override val name: String = "Rendering"

    override fun execute(context: PipelineContext): PipelineContext {
        val compiled =
            checkNotNull(context.compiled) { "RenderingStage requires compiled (Stage 2 Merge must run first)" }
        val variableBindings =
            checkNotNull(context.variableBindings) {
                "RenderingStage requires variableBindings (Stage 4 ResolveVariables must run first)"
            }
        val contextBindings =
            checkNotNull(context.contextBindings) {
                "RenderingStage requires contextBindings (Stage 5 ResolveContext must run first)"
            }
        val outputFormat = context.request.outputFormat ?: compiled.output?.format ?: OutputFormat.TEXT
        val rendered =
            renderEngine.render(
                compiled,
                variableBindings,
                contextBindings,
                outputFormat,
                context.request.outputSchema,
            )
        return context.copy(rendered = rendered)
    }
}
