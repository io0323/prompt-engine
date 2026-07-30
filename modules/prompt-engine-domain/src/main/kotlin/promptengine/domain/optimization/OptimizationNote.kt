package promptengine.domain.optimization

import promptengine.domain.shared.TokenCount

/**
 * 1つの[OptimizationRule]が適用されたことの記録（設計書§2.11「変更内容はOptimizationReport」）。
 */
data class OptimizationNote(
    val ruleId: String,
    val tokensSaved: TokenCount,
    val detail: String,
) {
    init {
        require(ruleId.isNotBlank()) { "ruleId must not be blank" }
    }
}
