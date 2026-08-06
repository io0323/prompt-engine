package promptengine.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.event.DomainEvent
import promptengine.domain.event.EventBusAdapter
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * [EventBusAdapter]の本番実装（ADR-0025決定1・決定6、Issue #35クローズ）。
 *
 * `publish()`はKafka等のBrokerへ直接送信せず、`event_bus_outbox`へ1行INSERTするだけの
 * 薄い実装とする。実際のBroker送信は[OutboxRelayer]が非同期に行う。これは
 * [EvaluationStage][promptengine.application.pipeline.EvaluationStage]が`publish()`を
 * `runCatching`で包み「本流を失敗させない」契約を持つため、Broker接続不調がPipeline本流の
 * レイテンシへ波及しないようにする狙い。
 *
 * 呼出元（`EvaluationStage`）は`publish()`を独自のトランザクション無しで呼ぶため、
 * 本クラス自身が[transactionTemplate]で短いトランザクションを開いてINSERTする。
 *
 * （命名について: 本クラス自体はKafkaクライアントに一切依存しないため、実体を表す
 * `OutboxEventBusAdapter`という名前を採用した。Kafkaへの依存は[OutboxRelayer]・
 * [KafkaEventProducer]側に閉じる。ADR-0025決定6参照。）
 */
class OutboxEventBusAdapter(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : EventBusAdapter {
    override fun publish(event: DomainEvent) {
        val now = Instant.now()
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(
                """
                INSERT INTO event_bus_outbox
                    (outbox_id, event_id, event_type, aggregate_type, aggregate_id,
                     actor, trace_id, payload, occurred_at, created_at)
                VALUES
                    (:outboxId, :eventId, :eventType, :aggregateType, :aggregateId,
                     :actor, :traceId, :payload::json, :occurredAt, :createdAt)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("outboxId", UUID.randomUUID())
                    .addValue("eventId", event.eventId)
                    .addValue("eventType", event.eventType)
                    .addValue("aggregateType", event.aggregateType)
                    .addValue("aggregateId", event.aggregateId)
                    .addValue("actor", event.actor)
                    .addValue("traceId", event.traceId)
                    .addValue("payload", objectMapper.writeValueAsString(event.payload))
                    .addValue("occurredAt", Timestamp.from(event.occurredAt))
                    .addValue("createdAt", Timestamp.from(now)),
            )
        }
    }
}
