package promptengine.domain.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.TokenCount
import promptengine.domain.variable.BindingSet

/**
 * Optimizationの入口（設計書§2.6ステージ7・§5.6シーケンス）。
 *
 * 登録済み全[OptimizationRule]のうち[OptimizationRule.applicable]がtrueのものを順に適用し、
 * 最終的な見積りTokenがなお[budget]を超える場合は[TokenBudgetExceededException]を投げる
 * （ADR-0013決定9。[promptengine.domain.validation.ValidationEngine]と異なり、ここは
 * 予算超過をパイプライン続行不可の分岐点として扱うため例外を投げる設計とする）。
 *
 * [variableBindings]は各[OptimizationRule]へは渡さないが、実際にRenderされる全文の
 * TokenEstimate算出（Rule適用前後の見積り直し）には必要なため、Engine自身の引数として
 * 受け取る。
 */
interface OptimizationEngine {
    fun optimize(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        budget: TokenCount,
    ): OptimizationOutcome
}
