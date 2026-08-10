package promptengine.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditQuery
import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import promptengine.domain.shared.Page
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * [AuditRepository]のJDBC実装（`audit_logs`テーブル、設計書§12、ADR-0017、
 * [Issue #35](https://github.com/io0323/prompt-engine/issues/35)をクローズ）。
 *
 * [append]（Pipeline Stage 12専用の狭い[AuditRecord]）は`aggregateType="PipelineExecution"`、
 * `action`にモード名、`payload`に`stageDurationsMs`/`outcome`をJSONで格納した一般行として
 * 永続化する。[record]（governance操作用の一般形[AuditLogEntry]）はそのまま列へ写す。
 * いずれも`aggregateId`はビジネスキー文字列（`PromptKey.value`）で受け取り、
 * `domain_events`と同じ方針（V1マイグレーションのコメント参照）で`prompts.prompt_id`
 * （UUID）へ解決してから`audit_logs.aggregate_id`へ書き込む（ADR-0017）。
 */
class JdbcAuditRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : AuditRepository {
    override fun append(record: AuditRecord) {
        val aggregateId = record.promptKey?.let { findPromptId(it) } ?: NIL_PROMPT_ID
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "stageDurationsMs" to record.stageDurationsMs,
                    "outcome" to outcomePayload(record.outcome),
                ),
            )
        insert(
            AuditLogRow(
                aggregateType = "PipelineExecution",
                aggregateId = aggregateId,
                action = record.mode.name,
                actor = "system",
                payload = payload,
                traceId = record.traceId,
                occurredAt = record.occurredAt,
            ),
        )
    }

    override fun record(entry: AuditLogEntry) {
        insert(
            AuditLogRow(
                aggregateType = entry.aggregateType,
                aggregateId = findPromptId(entry.aggregateId),
                action = entry.action,
                actor = entry.actor,
                payload = entry.payload,
                traceId = entry.traceId,
                occurredAt = entry.occurredAt,
                eventId = entry.eventId,
            ),
        )
    }

    override fun search(query: AuditQuery): Page<AuditLogEntry> {
        val conditions = mutableListOf<String>()
        val params = MapSqlParameterSource()
        if (query.aggregateId != null) {
            conditions += "p.prompt_key = :aggregateId"
            params.addValue("aggregateId", query.aggregateId)
        }
        if (query.actor != null) {
            conditions += "al.actor = :actor"
            params.addValue("actor", query.actor)
        }
        if (query.from != null) {
            conditions += "al.occurred_at >= :from"
            params.addValue("from", Timestamp.from(query.from))
        }
        if (query.to != null) {
            conditions += "al.occurred_at <= :to"
            params.addValue("to", Timestamp.from(query.to))
        }
        val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"

        // NIL_PROMPT_ID（promptKeyがnullの記録）で書かれた行はprompts側に対応するUUIDが
        // 存在しない。INNER JOINだとこれらの行がsearchから恒久的に見えなくなる
        // （書き込みは成功するのに検索できない、というCodeRabbitレビュー指摘）ため、
        // LEFT JOINにしaggregate_idは該当prompt_keyが無ければ空文字列を返す。
        val total =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs al LEFT JOIN prompts p ON p.prompt_id = al.aggregate_id $whereClause",
                params,
                Long::class.java,
            ) ?: 0L

        params.addValue("limit", query.size).addValue("offset", query.page.toLong() * query.size)
        val items =
            jdbcTemplate.query(
                """
                SELECT al.audit_id, al.aggregate_type, p.prompt_key AS aggregate_id, al.action, al.actor,
                       al.payload::text AS payload, al.trace_id, al.occurred_at, al.event_id
                FROM audit_logs al
                LEFT JOIN prompts p ON p.prompt_id = al.aggregate_id
                $whereClause
                ORDER BY al.occurred_at DESC, al.audit_id DESC
                LIMIT :limit OFFSET :offset
                """.trimIndent(),
                params,
            ) { rs, _ ->
                AuditLogEntry(
                    auditId = rs.getObject("audit_id", UUID::class.java),
                    aggregateType = rs.getString("aggregate_type"),
                    aggregateId = rs.getString("aggregate_id") ?: "",
                    action = rs.getString("action"),
                    actor = rs.getString("actor"),
                    payload = rs.getString("payload"),
                    traceId = rs.getString("trace_id"),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                    eventId = rs.getObject("event_id", UUID::class.java),
                )
            }
        return Page(items, query.page, query.size, total)
    }

    private data class AuditLogRow(
        val aggregateType: String,
        val aggregateId: UUID,
        val action: String,
        val actor: String,
        val payload: String,
        val traceId: String,
        val occurredAt: Instant,
        val eventId: UUID? = null,
    )

    /**
     * `ON CONFLICT (event_id) DO NOTHING`（V13の一意制約）で書き、Broker由来の同一イベントが
     * 再配信されても監査行が二重にならないようにする（ADR-0025決定8）。
     * `event_id`が`null`（Pipeline Stage 12・CRUD経路。キーにできるイベントを持たない）の行は、
     * PostgreSQLがUNIQUE制約上NULLを互いに異なる値として扱うため、この`ON CONFLICT`句に
     * 掛からず常に挿入される。
     */
    private fun insert(row: AuditLogRow) {
        jdbcTemplate.update(
            """
            INSERT INTO audit_logs
                (audit_id, aggregate_type, aggregate_id, action, actor, payload, trace_id, occurred_at, event_id)
            VALUES
                (:auditId, :aggregateType, :aggregateId, :action, :actor, :payload::json, :traceId, :occurredAt, :eventId)
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("auditId", UUID.randomUUID())
                .addValue("aggregateType", row.aggregateType)
                .addValue("aggregateId", row.aggregateId)
                .addValue("action", row.action)
                .addValue("actor", row.actor)
                .addValue("payload", row.payload)
                .addValue("traceId", row.traceId)
                .addValue("occurredAt", Timestamp.from(row.occurredAt))
                .addValue("eventId", row.eventId),
        )
    }

    private fun outcomePayload(outcome: AuditOutcome): Map<String, String?> =
        when (outcome) {
            is AuditOutcome.Success -> mapOf("type" to "Success", "errorCode" to null)
            is AuditOutcome.Failure -> mapOf("type" to "Failure", "errorCode" to outcome.errorCode)
        }

    private fun findPromptId(promptKey: String): UUID =
        jdbcTemplate
            .query(
                "SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey",
                MapSqlParameterSource().addValue("promptKey", promptKey),
            ) { rs, _ -> rs.getObject("prompt_id", UUID::class.java) }
            .singleOrNull() ?: error("prompt not found for audit aggregateId=$promptKey")

    private companion object {
        /** [AuditRecord.promptKey]が`null`の場合（呼出元の防御的な設計上あり得る）のプレースホルダー。 */
        val NIL_PROMPT_ID: UUID = UUID(0, 0)
    }
}
