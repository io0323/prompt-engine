package promptengine.domain.optimization

/**
 * [OptimizationEngine.optimize]の集約結果（設計書§2.11）。
 */
@ConsistentCopyVisibility
data class OptimizationReport private constructor(
    val appliedRules: List<OptimizationNote>,
    val truncations: List<TruncationNote>,
) {
    companion object {
        /** 何も適用されなかった場合の空の[OptimizationReport]を返す。 */
        fun empty(): OptimizationReport = OptimizationReport(emptyList(), emptyList())

        /** [appliedRules]・[truncations]を不変コピー（[List.toList]）してから保持する。 */
        operator fun invoke(
            appliedRules: List<OptimizationNote>,
            truncations: List<TruncationNote> = emptyList(),
        ): OptimizationReport = OptimizationReport(appliedRules.toList(), truncations.toList())
    }
}
