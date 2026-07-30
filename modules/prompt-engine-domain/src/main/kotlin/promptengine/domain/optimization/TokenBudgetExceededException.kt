package promptengine.domain.optimization

import promptengine.domain.shared.TokenCount

/**
 * 最適化後もなお見積りTokenが予算を超える（設計書§13.3 `TOKEN_BUDGET_EXCEEDED`、
 * §5.6シーケンス`alt estimate > budget`分岐）。
 */
class TokenBudgetExceededException(val estimated: TokenCount, val budget: TokenCount) :
    RuntimeException("TOKEN_BUDGET_EXCEEDED: estimated ${estimated.value} exceeds budget ${budget.value}")
