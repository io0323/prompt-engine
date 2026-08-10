package promptengine.engine.evaluation

import promptengine.domain.evaluation.EvaluationEngine
import promptengine.domain.evaluation.EvaluationRecord
import promptengine.domain.evaluation.EvaluationRule
import promptengine.domain.evaluation.EvaluationRuleFailureHandler
import promptengine.domain.evaluation.PromptExecutionSummary
import java.time.Clock
import java.time.Instant

/**
 * [EvaluationEngine]の実装（設計書§2.12、ADR-0026決定3）。
 *
 * 登録された[EvaluationRule]を順に適用するだけで、個々の指標の意味を知らない。
 * M1では[LatencyEvaluationRule]/[TokenUsageEvaluationRule]/[CostEvaluationRule]の3つを
 * `prompt-engine-bootstrap`が注入する。Quality系（設計書§2.12のPrompt Quality /
 * Response Quality等）は[EvaluationRule]を実装したPluginを[rules]へ足すだけで
 * 追加できる（本クラスの変更を要さない）。
 *
 * Broker購読・永続化・`PromptEvaluationCompleted`の発行は本クラスの責務ではなく、
 * `prompt-engine-infrastructure`の購読側が担う。本クラスは副作用の無い変換に徹する。
 *
 * [rules]は構築時に不変コピーを取る（呼出元のMutableList変更から隔離）。
 */
class EvaluationEngineImpl(
    rules: List<EvaluationRule>,
    private val failureHandler: EvaluationRuleFailureHandler,
    private val clock: Clock = Clock.systemUTC(),
) : EvaluationEngine {
    private val rules: List<EvaluationRule> = rules.toList()

    init {
        val duplicated = this.rules.groupBy { it.metricType }.filterValues { it.size > 1 }.keys
        // metricTypeの重複は evaluation_records の冪等キー (event_id, metric_type) と衝突し、
        // 2つ目以降の評価器の結果が ON CONFLICT DO NOTHING で恒久的に捨てられる。
        // 設定ミスとして起動時にfail-fastさせる。
        require(duplicated.isEmpty()) { "EvaluationRule metricType must be unique, duplicated: $duplicated" }
    }

    override fun evaluate(execution: PromptExecutionSummary): List<EvaluationRecord> {
        val evaluatedAt = Instant.now(clock)
        return rules.mapNotNull { rule -> applyRule(rule, execution, evaluatedAt) }
    }

    /**
     * 1つの評価器の失敗が他の評価器の結果を巻き添えにしないよう、評価器単位で例外を捕捉する。
     * 握り潰さず[failureHandler]へ委譲する（`prompt-engine-core`はSLF4Jへ依存できないため、
     * ログ出力は`prompt-engine-infrastructure`側の実装が行う）。
     */
    private fun applyRule(
        rule: EvaluationRule,
        execution: PromptExecutionSummary,
        evaluatedAt: Instant,
    ): EvaluationRecord? {
        val score =
            runCatching { rule.evaluate(execution) }
                .getOrElse { cause ->
                    runCatching { failureHandler.handle(rule.metricType, execution, cause) }
                    null
                }
        return score?.let {
            EvaluationRecord(
                eventId = execution.eventId,
                promptKey = execution.promptKey,
                semVer = execution.semVer,
                metricType = rule.metricType,
                score = it,
                method = rule.method,
                sampleRef = execution.traceId,
                evaluatedAt = evaluatedAt,
            )
        }
    }
}
