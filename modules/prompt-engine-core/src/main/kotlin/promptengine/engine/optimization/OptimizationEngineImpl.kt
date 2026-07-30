package promptengine.engine.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.optimization.OptimizationEngine
import promptengine.domain.optimization.OptimizationNote
import promptengine.domain.optimization.OptimizationOutcome
import promptengine.domain.optimization.OptimizationReport
import promptengine.domain.optimization.OptimizationRule
import promptengine.domain.optimization.TokenBudgetExceededException
import promptengine.domain.optimization.TruncationNote
import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.BindingSet
import promptengine.engine.validation.AstTextEstimator

/**
 * Optimizationの入口実装（設計書§5.6シーケンス、ADR-0013決定9）。
 *
 * [rules]は登録順に評価する（Chain of Responsibilityではなく単純なloop適用）。各Rule適用後に
 * TokenEstimateを再計算してから次のRuleの`applicable`を評価する（あるRuleの適用が
 * 後続Ruleの適用条件に影響しうるため、固定値ではなく毎回最新の値を使う）。
 */
class OptimizationEngineImpl(
    private val rules: List<OptimizationRule>,
    private val tokenizerPlugin: TokenizerPlugin,
) : OptimizationEngine {
    override fun optimize(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        budget: TokenCount,
    ): OptimizationOutcome {
        var currentCompiled = compiled
        var currentContext = contextBindings
        val appliedNotes = mutableListOf<OptimizationNote>()
        val truncations = mutableListOf<TruncationNote>()

        fun estimate(): TokenCount {
            val text = AstTextEstimator.estimate(currentCompiled.body, variableBindings, currentContext)
            return tokenizerPlugin.estimate(text)
        }

        for (rule in rules) {
            val currentEstimate = estimate()
            if (rule.applicable(currentCompiled, currentContext, profile, currentEstimate, budget)) {
                val result = rule.optimize(currentCompiled, currentContext, profile, currentEstimate, budget)
                currentCompiled = result.compiled
                currentContext = result.contextBindings
                appliedNotes += result.note
                truncations += result.truncations
            }
        }

        val finalEstimate = estimate()
        if (finalEstimate.value > budget.value) {
            throw TokenBudgetExceededException(finalEstimate, budget)
        }

        return OptimizationOutcome(
            currentCompiled,
            currentContext,
            finalEstimate,
            OptimizationReport(appliedNotes, truncations),
        )
    }
}
