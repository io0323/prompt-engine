package promptengine.engine.evaluation

import promptengine.domain.evaluation.EvaluationRule
import promptengine.domain.evaluation.PromptExecutionSummary
import java.math.BigDecimal

/**
 * Latency評価器（設計書§2.12「Latency | Execution Stage実測」）。
 *
 * スコアはStage 9（Execution）の実測ミリ秒そのもの。設計書§2.12はp50/p95/p99の
 * パーセンタイルに言及するが、それは複数の`evaluation_records`行を跨いだ集計
 * （Monitoring/分析側の責務、10cスコープ）であり、1実行あたりの評価器としては
 * 実測値をそのまま記録する。
 */
class LatencyEvaluationRule : EvaluationRule {
    override val metricType: String = METRIC_TYPE
    override val method: String = METHOD

    override fun evaluate(execution: PromptExecutionSummary): BigDecimal = BigDecimal.valueOf(execution.latency.value)

    companion object {
        const val METRIC_TYPE = "Latency"
        const val METHOD = "execution-stage-measured"
    }
}
