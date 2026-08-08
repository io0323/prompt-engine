package promptengine.infrastructure.messaging

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 既存`outbox`（V1、ADR-0006のAggregate Event Store）を対象にした[OutboxSource]。
 * クレーム対象の選定・リトライ制御に使う列（`dispatched_at`/`claimed_at`/`attempts`/
 * `next_attempt_at`）は`outbox`自身が持つ（V12）が、封筒フィールド（`event_type`・
 * `aggregate_type`・`payload`等）は`domain_events`側にしか無いため、クレーム後に
 * `domain_events`をJOINして取得する（同一トランザクション内、ADR-0025決定2）。
 *
 * `domain_events.aggregate_id`はPrompt Aggregateの永続化層サロゲートキー（UUID、ADR-0006）
 * であり、[OutboxEnvelope.aggregateId]としてはそのまま文字列化して使う
 * （Broker側はビジネスキーかサロゲートキーかを区別しない不透明な識別子として扱う）。
 */
class DomainEventOutboxSource(
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
            val claimedIds =
                jdbcTemplate.queryForList(
                    """
                    UPDATE outbox
                    SET claimed_at = :now, claimed_by = :instanceId
                    WHERE outbox_id IN (
                        SELECT outbox_id FROM outbox
                        WHERE dispatched_at IS NULL
                          AND next_attempt_at <= :now
                          AND (claimed_at IS NULL OR claimed_at < :claimStaleBefore)
                        ORDER BY created_at
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING outbox_id, event_id, attempts
                    """.trimIndent(),
                    MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("instanceId", instanceId)
                        .addValue("claimStaleBefore", Timestamp.from(claimStaleBefore))
                        .addValue("batchSize", batchSize),
                )
            if (claimedIds.isEmpty()) return@execute emptyList()

            // claimedIdsの順序（claimBatchのORDER BY created_at）を、この後のIN句クエリの
            // 結果順序としてそのまま使う。SQLの `IN (:eventIds)` は行の返却順序を保証しないため
            // （CodeRabbitレビュー指摘）、DB結果はeventId→行のMapとしてのみ使い、実際の出力順は
            // claimedIds（クレーム順）でこちら側から明示的に組み立てる。
            val claimOrder = claimedIds.map { it["event_id"] as UUID }
            val attemptsByEventId = claimedIds.associate { it["event_id"] as UUID to it["attempts"] as Int }
            val outboxIdByEventId = claimedIds.associate { it["event_id"] as UUID to it["outbox_id"] as UUID }

            val domainEventsByEventId =
                jdbcTemplate.query(
                    """
                    SELECT event_id, event_type, aggregate_type, aggregate_id, actor, trace_id,
                           payload::text AS payload, occurred_at
                    FROM domain_events
                    WHERE event_id IN (:eventIds)
                    """.trimIndent(),
                    MapSqlParameterSource("eventIds", claimOrder),
                ) { rs, _ ->
                    val eventId = rs.getObject("event_id", UUID::class.java)
                    eventId to
                        OutboxEnvelope(
                            outboxId = outboxIdByEventId.getValue(eventId),
                            eventId = eventId,
                            eventType = rs.getString("event_type"),
                            aggregateType = rs.getString("aggregate_type"),
                            aggregateId = rs.getObject("aggregate_id", UUID::class.java).toString(),
                            actor = rs.getString("actor"),
                            traceId = rs.getString("trace_id"),
                            payload = rs.getString("payload"),
                            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                            attempts = attemptsByEventId.getValue(eventId),
                        )
                }.toMap()

            claimOrder.map { eventId -> domainEventsByEventId.getValue(eventId) }
        } ?: emptyList()

    override fun markDispatched(
        outboxId: UUID,
        instanceId: String,
    ): Boolean =
        transactionTemplate.execute {
            jdbcTemplate.update(
                """
                UPDATE outbox SET dispatched_at = :now
                WHERE outbox_id = :outboxId AND claimed_by = :instanceId
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("now", Timestamp.from(Instant.now()))
                    .addValue("outboxId", outboxId)
                    .addValue("instanceId", instanceId),
            )
        } == 1

    override fun markFailed(
        outboxId: UUID,
        instanceId: String,
        nextAttemptAt: Instant,
    ): Boolean =
        transactionTemplate.execute {
            jdbcTemplate.update(
                """
                UPDATE outbox
                SET claimed_at = NULL, claimed_by = NULL, attempts = attempts + 1, next_attempt_at = :nextAttemptAt
                WHERE outbox_id = :outboxId AND claimed_by = :instanceId
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                    .addValue("outboxId", outboxId)
                    .addValue("instanceId", instanceId),
            )
        } == 1
}
