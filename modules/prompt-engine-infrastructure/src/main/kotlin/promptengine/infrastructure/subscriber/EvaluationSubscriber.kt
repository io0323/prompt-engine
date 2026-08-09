package promptengine.infrastructure.subscriber

import promptengine.domain.evaluation.EvaluationEngine
import promptengine.domain.evaluation.EvaluationRecord
import promptengine.domain.evaluation.EvaluationRepository
import promptengine.domain.evaluation.PromptEvaluationCompletedEvent
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.event.EventEnvelope
import promptengine.domain.event.EventSubscriber
import promptengine.domain.event.EventTopic
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * `PromptExecuted`を購読して非同期に評価を行い、結果を`evaluation_records`へ保存したうえで
 * `PromptEvaluationCompleted`を発行する（設計書§2.12・§14、ADR-0026決定3）。
 *
 * 評価そのもの（どんな指標をどう算出するか）は[EvaluationEngine]（実装は`prompt-engine-core`）の
 * 責務。本クラスはBroker購読・永続化・イベント発行という配管だけを担う。この分割により
 * `prompt-engine-infrastructure`が`prompt-engine-core`へモジュール依存を持たずに済む
 * （domainが定義した[EvaluationEngine]インターフェースだけを参照する）。
 *
 * ## 本流をブロックしないこと
 * Pipeline Stage 11（`EvaluationStage`）は`EventBusAdapter.publish`でイベントを出すだけで
 * 本クラスを同期呼び出ししない。本クラスはBrokerのポーリングループ上で完全に非同期に動く。
 *
 * ## 冪等性
 * [EvaluationRepository.saveAll]が`ON CONFLICT (event_id, metric_type) DO NOTHING`（V13）で
 * 書くため、同一イベントの再配信で行が二重にならない（ADR-0025決定8）。
 * 保存が0件（＝全て重複）だった場合は`PromptEvaluationCompleted`を再発行しない。
 * 再配信のたびに下流へ完了イベントが増殖するのを避けるため。
 */
class EvaluationSubscriber(
    private val evaluationEngine: EvaluationEngine,
    private val evaluationRepository: EvaluationRepository,
    private val eventBusAdapter: EventBusAdapter,
    private val payloadCodec: PromptExecutedPayloadCodec,
    private val clock: Clock = Clock.systemUTC(),
) : EventSubscriber {
    override val name: String = SUBSCRIBER_NAME

    override val topics: Set<EventTopic> = setOf(EventTopic.PE_EXECUTION)

    override fun handle(envelope: EventEnvelope) {
        // pe.execution には PromptExecuted 以外のイベントも流れる（ADR-0025決定7）。
        if (envelope.eventType != PROMPT_EXECUTED) return

        val records = evaluationEngine.evaluate(payloadCodec.decode(envelope))
        // saveAll が0件（＝全て重複、再配信）の場合は完了イベントを再発行しない。
        // 再配信のたびに下流へ完了イベントが増殖するのを避けるため。
        val inserted = if (records.isEmpty()) 0 else evaluationRepository.saveAll(records)
        if (inserted > 0) {
            eventBusAdapter.publish(completedEvent(envelope, records))
        }
    }

    private fun completedEvent(
        envelope: EventEnvelope,
        records: List<EvaluationRecord>,
    ): PromptEvaluationCompletedEvent {
        val first = records.first()
        return PromptEvaluationCompletedEvent(
            eventId = UUID.randomUUID(),
            occurredAt = Instant.now(clock),
            aggregateId = envelope.aggregateId,
            actor = envelope.actor,
            traceId = envelope.traceId,
            payload =
                PromptEvaluationCompletedEvent.Payload(
                    promptKey = first.promptKey,
                    semVer = first.semVer,
                    sourceEventId = envelope.eventId,
                    metrics =
                        records.map {
                            PromptEvaluationCompletedEvent.Metric(it.metricType, it.score, it.method)
                        },
                ),
        )
    }

    companion object {
        /** Brokerのconsumer group IDおよび`dead_letter_queue.subscriber_name`。 */
        const val SUBSCRIBER_NAME = "pe-evaluation-engine"
        private const val PROMPT_EXECUTED = "PromptExecuted"
    }
}
