package promptengine.application.pipeline

import promptengine.domain.event.EventBusAdapter
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.pipeline.PromptExecutedEvent
import java.time.Instant
import java.util.UUID

/**
 * Stage 11（Evaluation、設計書§2.6「イベント発行のみ（非同期評価）」）。
 *
 * 同期的にEvaluation処理を行わず、[eventBusAdapter]へ[PromptExecutedEvent]を発行するのみ
 * （実装ガイド§6.9「Evaluationステージは同期処理せず、イベント発行のみ」）。本流を
 * ブロックしない・失敗させない契約とする（設計書§2.6「記録のみ、本流は失敗させない」）ため、
 * payload構築（[checkNotNull]・`outcome.attempts.last()`等）と[EventBusAdapter.publish]の
 * 両方を同一の[runCatching]で包む（CodeRabbitレビュー指摘: `publish`のみを保護すると、
 * payload構築側で発生した例外——例えば`attempts`が空のケースの`last()`——がこのStageの
 * 外へ伝播し、「本流を失敗させない」契約に反してしまう）。
 */
class EvaluationStage(
    private val eventBusAdapter: EventBusAdapter,
    private val actor: String = DEFAULT_ACTOR,
) : PipelineStage {
    override val name: String = "Evaluation"

    override fun execute(context: PipelineContext): PipelineContext {
        runCatching {
            val outcome =
                checkNotNull(context.executionOutcome) {
                    "EvaluationStage requires executionOutcome (Stage 9 Execution must run first)"
                }
            val usage = outcome.attempts.last().usage
            val event =
                PromptExecutedEvent(
                    eventId = UUID.randomUUID(),
                    occurredAt = Instant.now(),
                    aggregateId = context.request.promptKey.value,
                    actor = actor,
                    traceId = context.traceId,
                    payload =
                        PromptExecutedEvent.Payload(
                            promptKey = context.request.promptKey.value,
                            inputTokens = usage.inputTokens.value,
                            outputTokens = usage.outputTokens.value,
                            retryCount = outcome.attempts.sumOf { it.retryCount },
                        ),
                )
            eventBusAdapter.publish(event)
        }
        return context
    }

    companion object {
        private const val DEFAULT_ACTOR = "system"
    }
}
