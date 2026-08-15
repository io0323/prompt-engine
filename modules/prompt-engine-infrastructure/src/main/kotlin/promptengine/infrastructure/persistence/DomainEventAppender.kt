package promptengine.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.event.DomainEvent
import promptengine.domain.fragment.FragmentDomainEvent
import promptengine.domain.governance.ReviewCaseDomainEvent
import promptengine.domain.prompt.PromptDomainEvent
import promptengine.domain.template.TemplateDomainEvent
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `domain_events`への追記・`outbox`への追記（設計書§2.14・§3.4「Aggregate単位・イベント追記」、
 * ADR-0006）を行う共通処理。[EventStorePromptRepository.save]・
 * [JdbcPromptMetadataRepository.upsert]・`JdbcReviewCaseRepository.save`（ADR-0032）・
 * `JdbcTemplateRepository.save`/`JdbcFragmentRepository.save`（ADR-0033）が使う。
 *
 * 呼出元が同一トランザクション（[org.springframework.transaction.support.TransactionTemplate]）
 * の中で呼ぶことを前提とする。本関数自体はトランザクション境界を持たない。
 *
 * `MAX(sequence)`はread-then-writeであり、行ロック無しでは同一[aggregateId]への並行呼び出しが
 * 同じ`sequence`を読んで`(aggregate_id, sequence)`のUNIQUE制約違反を起こしうる（レビュー指摘）。
 * Aggregate自身の行を`SELECT ... FOR UPDATE`でロックしてから読むことで、同一Aggregateへの
 * 並行呼び出しを直列化する。ロック対象テーブルはAggregate種別ごとに異なる（[appendDomainEvents]は
 * `prompts`、[appendGovernanceDomainEvents]は`review_cases`、[appendTemplateDomainEvents]は
 * `templates`、[appendFragmentDomainEvents]は`fragments`）ため、挿入本体（[insertDomainEventRows]）
 * は共通化しつつロッククエリのみ呼出元ごとに分ける。
 */
private fun NamedParameterJdbcTemplate.insertDomainEventRows(
    objectMapper: ObjectMapper,
    aggregateId: UUID,
    events: List<DomainEvent>,
) {
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

internal fun NamedParameterJdbcTemplate.appendDomainEvents(
    objectMapper: ObjectMapper,
    aggregateId: UUID,
    events: List<PromptDomainEvent>,
) {
    if (events.isEmpty()) return
    queryForObject(
        "SELECT prompt_id FROM prompts WHERE prompt_id = :aggregateId FOR UPDATE",
        MapSqlParameterSource("aggregateId", aggregateId),
        UUID::class.java,
    )
    insertDomainEventRows(objectMapper, aggregateId, events)
}

/** [appendDomainEvents]の`ReviewCase`向け（`review_cases`行をロック対象とする、ADR-0032）。 */
internal fun NamedParameterJdbcTemplate.appendGovernanceDomainEvents(
    objectMapper: ObjectMapper,
    aggregateId: UUID,
    events: List<ReviewCaseDomainEvent>,
) {
    if (events.isEmpty()) return
    queryForObject(
        "SELECT review_id FROM review_cases WHERE review_id = :aggregateId FOR UPDATE",
        MapSqlParameterSource("aggregateId", aggregateId),
        UUID::class.java,
    )
    insertDomainEventRows(objectMapper, aggregateId, events)
}

/** [appendDomainEvents]の`Template`向け（`templates`行をロック対象とする、ADR-0033）。 */
internal fun NamedParameterJdbcTemplate.appendTemplateDomainEvents(
    objectMapper: ObjectMapper,
    aggregateId: UUID,
    events: List<TemplateDomainEvent>,
) {
    if (events.isEmpty()) return
    queryForObject(
        "SELECT template_id FROM templates WHERE template_id = :aggregateId FOR UPDATE",
        MapSqlParameterSource("aggregateId", aggregateId),
        UUID::class.java,
    )
    insertDomainEventRows(objectMapper, aggregateId, events)
}

/** [appendDomainEvents]の`Fragment`向け（`fragments`行をロック対象とする、ADR-0033）。 */
internal fun NamedParameterJdbcTemplate.appendFragmentDomainEvents(
    objectMapper: ObjectMapper,
    aggregateId: UUID,
    events: List<FragmentDomainEvent>,
) {
    if (events.isEmpty()) return
    queryForObject(
        "SELECT fragment_id FROM fragments WHERE fragment_id = :aggregateId FOR UPDATE",
        MapSqlParameterSource("aggregateId", aggregateId),
        UUID::class.java,
    )
    insertDomainEventRows(objectMapper, aggregateId, events)
}
