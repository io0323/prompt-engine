package promptengine.domain.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet

/**
 * [OptimizationEngine.optimize]の戻り値（設計書§2.6ステージ7「最適化済AST + TokenEstimate」、
 * ADR-0013決定9訂正）。
 */
data class OptimizationOutcome(
    val compiled: CompiledPrompt,
    val contextBindings: ContextBindingSet,
    val report: OptimizationReport,
)
