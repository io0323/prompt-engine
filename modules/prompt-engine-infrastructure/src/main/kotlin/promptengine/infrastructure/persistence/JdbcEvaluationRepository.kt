package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.evaluation.EvaluationRecord
import promptengine.domain.evaluation.EvaluationRepository
import java.sql.Timestamp
import java.util.UUID

/**
 * [EvaluationRepository]のJDBC実装（`evaluation_records`、設計書§12、ADR-0026決定3）。
 *
 * 冪等キーは`(event_id, metric_type)`（V13）。1つの`PromptExecuted`から複数の評価器が
 * 行を書くため`event_id`単独ではない（ADR-0025決定8をこのテーブルの粒度へ適用）。
 *
 * `variant_id`（Experiment、設計書§12）はM1では常に`NULL`。Experiment機能自体が未実装で、
 * 評価の起点である`PromptExecuted`もVariantを運ばないため。
 */
class JdbcEvaluationRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : EvaluationRepository {
    override fun saveAll(records: List<EvaluationRecord>): Int {
        if (records.isEmpty()) return 0
        // 同一イベント由来の複数指標は同じVersionを指すため、(promptKey, semVer)単位で
        // 1回だけ解決してN+1問い合わせを避ける。
        val versionIdByVersion =
            records.map { it.promptKey to it.semVer }.distinct().associateWith { (promptKey, semVer) ->
                jdbcTemplate.findVersionId(promptKey, semVer)
                    ?: throw PromptVersionNotResolvableException(promptKey, semVer)
            }
        return records.sumOf { record ->
            jdbcTemplate.update(
                """
                INSERT INTO evaluation_records
                    (evaluation_id, version_id, variant_id, metric_type, score, method, sample_ref,
                     evaluated_at, event_id)
                VALUES
                    (:evaluationId, :versionId, NULL, :metricType, :score, :method, :sampleRef,
                     :evaluatedAt, :eventId)
                ON CONFLICT (event_id, metric_type) DO NOTHING
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("evaluationId", UUID.randomUUID())
                    .addValue("versionId", versionIdByVersion.getValue(record.promptKey to record.semVer))
                    .addValue("metricType", record.metricType)
                    .addValue("score", record.score)
                    .addValue("method", record.method)
                    .addValue("sampleRef", record.sampleRef)
                    .addValue("evaluatedAt", Timestamp.from(record.evaluatedAt))
                    .addValue("eventId", record.eventId),
            )
        }
    }
}
