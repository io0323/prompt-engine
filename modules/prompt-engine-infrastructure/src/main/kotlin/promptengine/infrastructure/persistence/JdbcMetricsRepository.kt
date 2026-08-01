package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.metrics.MetricsRepository
import promptengine.domain.metrics.MetricsSummary
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/**
 * [MetricsRepository]のJDBC実装（`execution_logs`テーブル、設計書§12、ADR-0017）。
 */
class JdbcMetricsRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : MetricsRepository {
    override fun summarize(
        promptKey: PromptKey,
        from: Instant,
        to: Instant,
    ): MetricsSummary {
        val row =
            jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) AS execution_count,
                    COALESCE(SUM(CASE WHEN el.status = 'success' THEN 1 ELSE 0 END), 0) AS success_count,
                    COALESCE(SUM(el.input_tokens), 0) AS total_input_tokens,
                    COALESCE(SUM(el.output_tokens), 0) AS total_output_tokens,
                    COALESCE(SUM(el.cost), 0) AS total_cost,
                    COALESCE(AVG(el.latency_ms), 0) AS average_latency_ms
                FROM execution_logs el
                JOIN prompt_versions pv ON pv.version_id = el.version_id
                JOIN prompts p ON p.prompt_id = pv.prompt_id
                WHERE p.prompt_key = :promptKey AND el.executed_at BETWEEN :from AND :to
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("promptKey", promptKey.value)
                    .addValue("from", Timestamp.from(from))
                    .addValue("to", Timestamp.from(to)),
            ) { rs, _ ->
                MetricsSummary(
                    promptKey = promptKey,
                    from = from,
                    to = to,
                    executionCount = rs.getLong("execution_count"),
                    successCount = rs.getLong("success_count"),
                    totalInputTokens = TokenCount(rs.getInt("total_input_tokens")),
                    totalOutputTokens = TokenCount(rs.getInt("total_output_tokens")),
                    totalCost = Cost(rs.getBigDecimal("total_cost") ?: BigDecimal.ZERO),
                    averageLatency = LatencyMs(rs.getLong("average_latency_ms")),
                )
            }
        return row ?: MetricsSummary(
            promptKey = promptKey,
            from = from,
            to = to,
            executionCount = 0,
            successCount = 0,
            totalInputTokens = TokenCount(0),
            totalOutputTokens = TokenCount(0),
            totalCost = Cost(BigDecimal.ZERO),
            averageLatency = LatencyMs(0),
        )
    }
}
