package promptengine.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.prompt.PromptDomainEvent
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `domain_events`への追記・`outbox`への追記（設計書§2.14・§3.4「Aggregate単位・イベント追記」、
 * ADR-0006）を行う共通処理。[EventStorePromptRepository.save]と
 * [JdbcPromptMetadataRepository.upsert]の両方（Prompt Aggregateの状態変化を伴わない
 * メタデータ更新も[promptengine.domain.prompt.PromptUpdated]を発行するため、P9b）が使う。
 *
 * 呼出元が同一トランザクション（[org.springframework.transaction.support.TransactionTemplate]）
 * の中で呼ぶことを前提とする。本関数自体はトランザクション境界を持たない。
 */
internal fun NamedParameterJdbcTemplate.appendDomainEvents(
    objectMapper: ObjectMapper,
    aggregateId: UUID,
    events: List<PromptDomainEvent>,
) {
    if (events.isEmpty()) return
    val baseSequence =
        queryForObject(
            "SELECT COALESCE(MAX(sequence), 0) FROM domain_events WHERE aggregate_id = :aggregateId",
            MapSqlParameterSource("aggregateId", aggregateId),
            Long::class.java,
        )!!
    events.forEachIndexed { index, event ->
        // event.eventIdはAggregate側で発行済み（例: Prompt.publish内のUUID.randomUUID()）。
        // ここで新規採番すると、リトライ時に同一イベントが別IDで重複登録されうる。
        val eventId = event.eventId
        update(
            """
            INSERT INTO domain_events
                (event_id, aggregate_type, aggregate_id, sequence, event_type, actor, trace_id, payload, occurred_at)
            VALUES
                (:eventId, :aggregateType, :aggregateId, :sequence, :eventType, :actor, :traceId, :payload::json, :occurredAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("aggregateType", event.aggregateType)
                .addValue("aggregateId", aggregateId)
                .addValue("sequence", baseSequence + index + 1)
                .addValue("eventType", event.eventType)
                .addValue("actor", event.actor)
                .addValue("traceId", event.traceId)
                .addValue("payload", objectMapper.writeValueAsString(event.payload))
                .addValue("occurredAt", Timestamp.from(event.occurredAt)),
        )
        update(
            "INSERT INTO outbox (outbox_id, event_id, created_at) VALUES (:outboxId, :eventId, :createdAt)",
            MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("eventId", eventId)
                .addValue("createdAt", Timestamp.from(Instant.now())),
        )
    }
}
