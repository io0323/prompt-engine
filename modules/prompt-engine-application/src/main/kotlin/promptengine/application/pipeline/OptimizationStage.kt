package promptengine.application.pipeline

import promptengine.domain.optimization.OptimizationEngine
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage

/**
 * Stage 7（Optimization、設計書§2.6）。[OptimizationEngine]へ委譲する。
 *
 * `variableBindings`/`contextBindings`が`null`（Stage 4・5未実行）の場合は空既定へ
 * フォールバックせず`checkNotNull`で拒否する（他の8ステージと同じfail-fast方針、
 * ADR-0015決定4修正）。空既定へ黙って倒すと、未束縛のままTokenEstimateを算出・最適化
 * してしまい、実際の呼出パラメータ・Contextを反映しない誤った見積りが正常系として
 * 下流（Rendering）へ流れてしまう。
 */
class OptimizationStage(private val optimizationEngine: OptimizationEngine) : PipelineStage {
    override val name: String = "Optimization"

    override fun execute(context: PipelineContext): PipelineContext {
        val compiled =
            checkNotNull(context.compiled) { "OptimizationStage requires compiled (Stage 2 Merge must run first)" }
        val variableBindings =
            checkNotNull(context.variableBindings) {
                "OptimizationStage requires variableBindings (Stage 4 ResolveVariables must run first)"
            }
        val contextBindings =
            checkNotNull(context.contextBindings) {
                "OptimizationStage requires contextBindings (Stage 5 ResolveContext must run first)"
            }
        val outcome =
            optimizationEngine.optimize(
                compiled,
                variableBindings,
                contextBindings,
                context.request.modelProfile,
                context.request.budget,
            )
        return context.copy(compiled = outcome.compiled, contextBindings = outcome.contextBindings)
    }
}
