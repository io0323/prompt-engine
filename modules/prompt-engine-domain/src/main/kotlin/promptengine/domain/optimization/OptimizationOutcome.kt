package promptengine.domain.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.TokenCount

/**
 * [OptimizationEngine.optimize]の戻り値（設計書§2.6ステージ7「最適化済AST + TokenEstimate」、
 * ADR-0013決定9訂正）。
 *
 * [tokenEstimate]は全Rule適用後の最終見積り（`budget`以下であることが呼出元で
 * 保証済み、[TokenBudgetExceededException]参照）。呼出元（P8 Pipeline Orchestrator）が
 * 改めて見積り直す必要が無いよう、Engine内部で算出済みの値をそのまま公開する。
 */
data class OptimizationOutcome(
    val compiled: CompiledPrompt,
    val contextBindings: ContextBindingSet,
    val tokenEstimate: TokenCount,
    val report: OptimizationReport,
)
