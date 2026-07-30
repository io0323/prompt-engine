package promptengine.domain.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet

/**
 * [OptimizationRule.optimize]1回分の戻り値（設計書§2.11、ADR-0013決定9訂正）。
 *
 * [truncations]は`Compression`が切り詰めたスコープの記録であり、それ以外のRuleは
 * 空リストを返す。
 */
data class RuleOptimizationResult(
    val compiled: CompiledPrompt,
    val contextBindings: ContextBindingSet,
    val note: OptimizationNote,
    val truncations: List<TruncationNote> = emptyList(),
)
