package promptengine.engine.evaluation

import promptengine.domain.evaluation.EvaluationRule
import promptengine.domain.evaluation.PromptExecutionSummary
import java.math.BigDecimal

/**
 * Cost評価器（設計書§2.12「Token Usage / Cost | APAP応答のusage × Model Profile単価」の
 * Cost部分）。
 *
 * `(inputTokens + outputTokens) × costPerToken`で算出する。
 * [promptengine.domain.optimization.ModelProfile.costPerToken]は入力・出力を区別しない
 * 単一のブレンド単価であり、実際のプロバイダ課金にある入出力別レートを表現できない。
 * M1ではこの簡略化を受け入れる（設計書§2.12の記述「usage × Model Profile単価」自体が
 * 単価を単数で書いているため、設計書とは矛盾しない。ADR-0026「既知の限界」）。
 */
class CostEvaluationRule : EvaluationRule {
    override val metricType: String = METRIC_TYPE
    override val method: String = METHOD

    override fun evaluate(execution: PromptExecutionSummary): BigDecimal =
        execution.costPerToken.value.multiply(BigDecimal.valueOf(execution.totalTokens.toLong()))

    companion object {
        const val METRIC_TYPE = "Cost"
        const val METHOD = "usage-x-model-profile-rate"
    }
}
