package promptengine.domain.optimization

/**
 * [OptimizationEngine.optimize]の集約結果（設計書§2.11）。
 */
data class OptimizationReport(
    val appliedRules: List<OptimizationNote>,
    val truncations: List<TruncationNote> = emptyList(),
) {
    companion object {
        /** 何も適用されなかった場合の空の[OptimizationReport]を返す。 */
        fun empty(): OptimizationReport = OptimizationReport(emptyList())
    }
}
