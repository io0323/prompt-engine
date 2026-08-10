package promptengine.domain.evaluation

/**
 * 登録された[EvaluationRule]群を1回の実行要約に対して適用する評価の中核（設計書§2.12、
 * ADR-0026決定3）。
 *
 * 実装（`EvaluationEngineImpl`）は`prompt-engine-core`に置く。Broker購読・永続化・
 * `PromptEvaluationCompleted`の発行は`prompt-engine-infrastructure`の購読側
 * （`EvaluationSubscriber`）が担い、本Interfaceは「実行要約 → 評価結果」の純粋な変換だけを
 * 表す。この分割により、`prompt-engine-infrastructure`が`prompt-engine-core`へモジュール依存を
 * 持たずに済む（CLAUDE.md「core / infrastructure は domain が定義したInterfaceを実装する側」）。
 */
interface EvaluationEngine {
    /**
     * [execution]に対して全評価器を適用し、値を算出できた指標の[EvaluationRecord]だけを返す。
     * 個々の評価器が例外を投げても他の評価器の結果を巻き添えにしない契約とする。
     */
    fun evaluate(execution: PromptExecutionSummary): List<EvaluationRecord>
}
