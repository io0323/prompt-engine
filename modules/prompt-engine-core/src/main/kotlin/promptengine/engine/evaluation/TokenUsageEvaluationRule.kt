package promptengine.engine.evaluation

import promptengine.domain.evaluation.EvaluationRule
import promptengine.domain.evaluation.PromptExecutionSummary
import java.math.BigDecimal

/**
 * Token Usage評価器（設計書§2.12「Token Usage / Cost | APAP応答のusage × Model Profile単価」の
 * usage部分）。
 *
 * スコアは入力・出力トークンの合計。設計書§2.12は"Token Usage"と表記するが、
 * `evaluation_records.metric_type`には識別子として扱いやすい空白なしの`TokenUsage`を格納する
 * （設計書に無い判断、ADR-0026決定3）。入力・出力の内訳は同じイベントから書かれる
 * `execution_logs.input_tokens`/`output_tokens`側に残るため、評価指標としては合計のみを持つ。
 */
class TokenUsageEvaluationRule : EvaluationRule {
    override val metricType: String = METRIC_TYPE
    override val method: String = METHOD

    override fun evaluate(execution: PromptExecutionSummary): BigDecimal =
        BigDecimal.valueOf(execution.totalTokens.toLong())

    companion object {
        const val METRIC_TYPE = "TokenUsage"
        const val METHOD = "provider-usage"
    }
}
