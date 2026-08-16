package promptengine.infrastructure.subscriber

import promptengine.domain.evaluation.ExecutionLogEntry
import promptengine.domain.evaluation.ExecutionLogRepository
import promptengine.domain.event.EventEnvelope
import promptengine.domain.event.EventSubscriber
import promptengine.domain.event.EventTopic
import promptengine.domain.shared.Cost

/**
 * `PromptExecuted`を購読して`execution_logs`（設計書§12）へ1行追記する（ADR-0026決定1）。
 *
 * ## なぜStageではなくSubscriberなのか（ADR-0026決定1）
 * 設計書§14は`PromptExecuted`の購読先を「Evaluation Engine, Monitoring, Audit」と定めており、
 * `execution_logs`への記録はMonitoring側の非同期fan-outに当たる。Pipelineのステージは
 * 「既存Engineへの薄い委譲」に留める方針（`ExecutionStage`が確立した形）であり、
 * 既に`AuditRecord`の追記だけを担っている`AuditStage`へ`execution_logs`の書き込みまで
 * 足すと、1ステージが2つの独立した記録先を持つことになりこの形が崩れる。
 *
 * ## Cost（`execution_logs.cost`、NOT NULL）
 * `(inputTokens + outputTokens) × costPerToken`で算出する。`EvaluationEngine`のCost評価器と
 * 同じ式だが、両者は独立に動く（片方が落ちてももう片方は記録される、ADR-0026決定1）ため、
 * 依存させず各自で計算する。`ModelProfile.costPerToken`が入出力を区別しない単一のブレンド
 * 単価であることによる簡略化も同じ（ADR-0026「既知の限界」）。
 *
 * ## 冪等性
 * [ExecutionLogRepository.append]が`ON CONFLICT (event_id) DO NOTHING`（V13）で書くため、
 * 同一イベントの再配信で行が二重にならない（ADR-0025決定8）。
 */
class ExecutionLogSubscriber(
    private val executionLogRepository: ExecutionLogRepository,
    private val payloadCodec: PromptExecutedPayloadCodec,
) : EventSubscriber {
    override val name: String = SUBSCRIBER_NAME

    override val topics: Set<EventTopic> = setOf(EventTopic.PE_EXECUTION)

    override fun handle(envelope: EventEnvelope) {
        // pe.execution には PromptExecutionFailed / ResponseParsed / ResponseParseFailed も流れる
        // （ADR-0025決定7のルーティング表）。execution_logs の対象は PromptExecuted のみ。
        if (envelope.eventType != PROMPT_EXECUTED) return

        val execution = payloadCodec.decode(envelope)
        executionLogRepository.append(
            ExecutionLogEntry(
                eventId = execution.eventId,
                promptKey = execution.promptKey,
                semVer = execution.semVer,
                callerSystem = execution.callerSystem,
                traceId = execution.traceId,
                latency = execution.latency,
                usage = execution.usage,
                cost = Cost(execution.costPerToken.value.multiply(execution.totalTokens.toBigDecimal())),
                status = execution.status,
                executedAt = execution.occurredAt,
                variantId = execution.variantId,
            ),
        )
    }

    companion object {
        /** Brokerのconsumer group IDおよび`dead_letter_queue.subscriber_name`。 */
        const val SUBSCRIBER_NAME = "pe-execution-log"
        private const val PROMPT_EXECUTED = "PromptExecuted"
    }
}
