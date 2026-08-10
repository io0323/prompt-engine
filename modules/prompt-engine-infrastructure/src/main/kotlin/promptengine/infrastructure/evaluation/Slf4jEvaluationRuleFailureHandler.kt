package promptengine.infrastructure.evaluation

import org.slf4j.LoggerFactory
import promptengine.domain.evaluation.EvaluationRuleFailureHandler
import promptengine.domain.evaluation.PromptExecutionSummary

/**
 * [EvaluationRuleFailureHandler]のSLF4J実装（ADR-0026決定3）。
 *
 * `prompt-engine-core`（`EvaluationEngineImpl`）はSLF4Jへ直接依存できないため、
 * [promptengine.infrastructure.audit.Slf4jAuditFailureHandler]と全く同じ形で
 * `prompt-engine-infrastructure`側に置く。
 *
 * [cause]は`javaClass.simpleName`のみを通常のフォーマット引数として渡し、`Throwable`
 * オブジェクト自体はSLF4J呼び出しへ渡さない（SLF4Jは末尾引数がThrowableの場合、
 * メッセージに現れなくても`cause.message`とスタックトレースをログイベントへ添付する。
 * Plugin由来の評価器が例外メッセージに入力値を含める可能性を排除するため。
 * `Slf4jAuditFailureHandler`が確立した方針）。
 */
class Slf4jEvaluationRuleFailureHandler : EvaluationRuleFailureHandler {
    override fun handle(
        metricType: String,
        execution: PromptExecutionSummary,
        cause: Throwable,
    ) {
        logger.error(
            "evaluation_rule_failed metricType={} promptKey={} semVer={} eventId={} traceId={} cause={}",
            metricType,
            execution.promptKey,
            execution.semVer,
            execution.eventId,
            execution.traceId,
            cause.javaClass.simpleName,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(Slf4jEvaluationRuleFailureHandler::class.java)
    }
}
