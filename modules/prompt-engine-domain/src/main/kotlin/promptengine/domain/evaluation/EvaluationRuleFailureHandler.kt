package promptengine.domain.evaluation

/**
 * [EvaluationRule.evaluate]が例外を投げた際の処理（ADR-0026決定3）。
 *
 * `EvaluationEngine`実装は`prompt-engine-core`にあり、SLF4Jへ直接依存できない
 * （domainのみに依存するモジュール）。[promptengine.domain.audit.AuditFailureHandler]と
 * 全く同じ理由・同じ形の逃がし口として定義し、SLF4J実装は
 * `prompt-engine-infrastructure`に置く。
 *
 * 1つの評価器の失敗を握り潰して他の評価器の結果まで失うのを避けつつ、失敗が
 * 無言で消えることも避けるための契約。
 */
interface EvaluationRuleFailureHandler {
    /**
     * [metricType]の評価器が[cause]で失敗したことを記録する。
     * この呼出し自体は失敗させない契約とする。
     */
    fun handle(
        metricType: String,
        execution: PromptExecutionSummary,
        cause: Throwable,
    )
}
