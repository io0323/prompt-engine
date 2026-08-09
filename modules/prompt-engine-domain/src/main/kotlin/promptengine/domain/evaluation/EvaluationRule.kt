package promptengine.domain.evaluation

import java.math.BigDecimal

/**
 * 1つの評価指標を算出する評価器（設計書§2.12、ADR-0026決定3）。
 *
 * M1では`prompt-engine-core`が実行系メトリクス3種（Latency / TokenUsage / Cost）を実装する。
 * Quality系（LLM-as-Judge等、設計書§2.12）は後からPluginとして追加できるよう、本Interfaceを
 * 唯一の拡張点として定義する（`EvaluationEngine`実装は登録された[EvaluationRule]のリストを
 * 順に回すだけで、個々の指標を知らない）。
 *
 * 実装は副作用を持たない純粋関数であること。永続化・イベント発行は`EvaluationEngine`側の責務。
 */
interface EvaluationRule {
    /**
     * `evaluation_records.metric_type`へ記録する指標名。同一の`EvaluationEngine`に登録された
     * 評価器の間で一意であること（`evaluation_records`の冪等キーが`(event_id, metric_type)`の
     * ため、重複すると同一イベントに対する2つ目以降が`ON CONFLICT DO NOTHING`で捨てられる）。
     */
    val metricType: String

    /** `evaluation_records.method`へ記録する算出方法（設計書§2.12「評価方法」）。 */
    val method: String

    /**
     * [execution]から指標値を算出する。この評価器が[execution]に適用できない場合は`null`を
     * 返す（`evaluation_records`へ行を書かない）。「値が0である」ことと「算出できない」ことを
     * スコア0で潰さないための区別。
     */
    fun evaluate(execution: PromptExecutionSummary): BigDecimal?
}
