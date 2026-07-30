package promptengine.domain.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet

/**
 * [OptimizationRule.optimize]1回分の戻り値（設計書§2.11、ADR-0013決定9訂正）。
 *
 * [truncations]は`Compression`が切り詰めたスコープの記録であり、それ以外のRuleは
 * 空リストを返す。
 */
@ConsistentCopyVisibility
data class RuleOptimizationResult private constructor(
    val compiled: CompiledPrompt,
    val contextBindings: ContextBindingSet,
    val note: OptimizationNote,
    val truncations: List<TruncationNote>,
) {
    companion object {
        /** [truncations]を不変コピー（[List.toList]）してから保持する（呼出元のMutableList変更から隔離）。 */
        operator fun invoke(
            compiled: CompiledPrompt,
            contextBindings: ContextBindingSet,
            note: OptimizationNote,
            truncations: List<TruncationNote> = emptyList(),
        ): RuleOptimizationResult = RuleOptimizationResult(compiled, contextBindings, note, truncations.toList())
    }
}
