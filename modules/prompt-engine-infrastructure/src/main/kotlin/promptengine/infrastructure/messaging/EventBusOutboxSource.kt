package promptengine.infrastructure.messaging

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `event_bus_outbox`（[OutboxEventBusAdapter]が書く、ADR-0025決定1）を対象にした[OutboxSource]。
 * 封筒フィールドをすべて自テーブルに持つ自己完結型のため、[DomainEventOutboxSource]と異なり
 * JOINを必要としない。
 */
class EventBusOutboxSource(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : OutboxSource {
    override fun claimBatch(
        instanceId: String,
        claimTimeout: Duration,
        batchSize: Int,
    ): List<OutboxEnvelope> =
        transactionTemplate.execute {
            val now = Instant.now()
            val claimStaleBefore = now.minus(claimTimeout)
            jdbcTemplate.query(
                """
                UPDATE event_bus_outbox
                SET claimed_at = :now, claimed_by = :instanceId
                WHERE outbox_id IN (
                    SELECT outbox_id FROM event_bus_outbox
                    WHERE dispatched_at IS NULL
                      AND next_attempt_at <= :now
                      AND (claimed_at IS NULL OR claimed_at < :claimStaleBefore)
                    ORDER BY created_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING outbox_id, event_id, event_type, aggregate_type, aggregate_id,
                          actor, trace_id, payload::text AS payload, occurred_at, attempts
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("now", Timestamp.from(now))
                    .addValue("instanceId", instanceId)
                    .addValue("claimStaleBefore", Timestamp.from(claimStaleBefore))
                    .addValue("batchSize", batchSize),
            ) { rs, _ ->
                OutboxEnvelope(
                    outboxId = rs.getObject("outbox_id", UUID::class.java),
                    eventId = rs.getObject("event_id", UUID::class.java),
                    eventType = rs.getString("event_type"),
                    aggregateType = rs.getString("aggregate_type"),
                    aggregateId = rs.getString("aggregate_id"),
                    actor = rs.getString("actor"),
                    traceId = rs.getString("trace_id"),
                    payload = rs.getString("payload"),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    attempts = rs.getInt("attempts"),
                )
            }
        } ?: emptyList()

    override fun markDispatched(outboxId: UUID) {
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(
                "UPDATE event_bus_outbox SET dispatched_at = :now WHERE outbox_id = :outboxId",
                MapSqlParameterSource()
                    .addValue("now", Timestamp.from(Instant.now()))
                    .addValue("outboxId", outboxId),
            )
        }
    }

    override fun markFailed(
        outboxId: UUID,
        nextAttemptAt: Instant,
    ) {
        transactionTemplate.executeWithoutResult {
            jdbcTemplate.update(
                """
                UPDATE event_bus_outbox
                SET claimed_at = NULL, claimed_by = NULL, attempts = attempts + 1, next_attempt_at = :nextAttemptAt
                WHERE outbox_id = :outboxId
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                    .addValue("outboxId", outboxId),
            )
        }
    }
}
