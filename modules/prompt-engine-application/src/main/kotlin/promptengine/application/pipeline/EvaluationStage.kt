package promptengine.application.pipeline

import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.execution.ExecutionOutcome
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
            val promptVersion =
                checkNotNull(context.promptVersion) {
                    "EvaluationStage requires promptVersion (Stage 2 Load must run first)"
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
                            semVer = promptVersion.semVer,
                            inputTokens = usage.inputTokens.value,
                            outputTokens = usage.outputTokens.value,
                            retryCount = outcome.attempts.sumOf { it.retryCount },
                            latencyMs = executionLatencyMs(context, outcome),
                            costPerToken = context.request.modelProfile.costPerToken.value,
                            // このStageはStage 9（Execution）が成功しStage 11まで到達した場合にしか
                            // 実行されないため、常にSUCCESS。実行失敗は設計書§14の別イベント
                            // PromptExecutionFailedの担当だが、その発火元はM1時点で存在しない。
                            status = ExecutionStatus.SUCCESS,
                            // ADR-0034決定4。Experiment経由の実行のみ非null（LoadStage参照）。
                            variantId = context.experimentVariantId,
                            // ADR-0035決定5。renderHashには含まないため、実行記録側に残す。
                            temperature = context.rendered?.modelHints?.temperature,
                        ),
                )
            eventBusAdapter.publish(event)
        }
        return context
    }

    /**
     * Latencyは設計書§2.12「Execution Stage実測」に従い`PipelineOrchestrator`が計測した
     * Stage 9のdurationを使う。`PipelineOrchestrator`以外の経路（テストが直接Stageを
     * 実行する場合や、将来Stage列の構成が変わった場合）で"Execution"キーが未設定なら、
     * 各試行の[promptengine.domain.execution.RawResponse.latency]の合算へフォールバックする
     * （Adapter実測の合計であり、Stage全体のdurationよりわずかに小さいが、0で埋めるよりは
     * 実態に近い。ADR-0026決定3）。
     *
     * [outcome]は呼出元が`checkNotNull`済みのものを受け取る。`context.executionOutcome`を
     * ここで読み直すと、到達不能なnull分岐（と、その先の`?: 0L`）が生まれるため
     * （P10bの分岐カバレッジ監査で「到達不能」として検出し、引数で受け取る形へ変更した）。
     */
    private fun executionLatencyMs(
        context: PipelineContext,
        outcome: ExecutionOutcome,
    ): Long = context.stageDurationsMs[EXECUTION_STAGE_NAME] ?: outcome.attempts.sumOf { it.latency.value }

    companion object {
        private const val DEFAULT_ACTOR = "system"
        private const val EXECUTION_STAGE_NAME = "Execution"
    }
}
