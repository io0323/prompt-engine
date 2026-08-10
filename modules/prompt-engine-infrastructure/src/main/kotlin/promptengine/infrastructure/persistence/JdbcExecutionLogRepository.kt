package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.evaluation.ExecutionLogEntry
import promptengine.domain.evaluation.ExecutionLogRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * [ExecutionLogRepository]のJDBC実装（`execution_logs`、設計書§12、ADR-0026決定1）。
 *
 * [append]は`ON CONFLICT (event_id) DO NOTHING`（V13の一意制約）で書き、同一イベントの
 * 再配信で行が二重にならないようにする（ADR-0025決定8）。
 */
class JdbcExecutionLogRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : ExecutionLogRepository {
    override fun append(entry: ExecutionLogEntry): Boolean {
        val versionId =
            jdbcTemplate.findVersionId(entry.promptKey, entry.semVer)
                ?: throw PromptVersionNotResolvableException(entry.promptKey, entry.semVer)
        val updated =
            jdbcTemplate.update(
                """
                INSERT INTO execution_logs
                    (execution_id, version_id, caller_system, trace_id, latency_ms,
                     input_tokens, output_tokens, cost, status, executed_at, event_id)
                VALUES
                    (:executionId, :versionId, :callerSystem, :traceId, :latencyMs,
                     :inputTokens, :outputTokens, :cost, :status, :executedAt, :eventId)
                ON CONFLICT (event_id) DO NOTHING
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("executionId", UUID.randomUUID())
                    .addValue("versionId", versionId)
                    .addValue("callerSystem", entry.callerSystem)
                    .addValue("traceId", entry.traceId)
                    .addValue("latencyMs", entry.latency.value)
                    .addValue("inputTokens", entry.usage.inputTokens.value)
                    .addValue("outputTokens", entry.usage.outputTokens.value)
                    .addValue("cost", entry.cost.value)
                    .addValue("status", entry.status.name)
                    .addValue("executedAt", Timestamp.from(entry.executedAt))
                    .addValue("eventId", entry.eventId),
            )
        return updated == 1
    }

    override fun hasExecutionSince(
        key: PromptKey,
        semVer: SemVer,
        since: Instant,
    ): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM execution_logs el
                JOIN prompt_versions pv ON pv.version_id = el.version_id
                JOIN prompts p ON p.prompt_id = pv.prompt_id
                WHERE p.prompt_key = :promptKey AND pv.version = :version AND el.executed_at >= :since
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("promptKey", key.value)
                .addValue("version", semVer.toString())
                .addValue("since", Timestamp.from(since)),
            Boolean::class.java,
        ) ?: false
}
