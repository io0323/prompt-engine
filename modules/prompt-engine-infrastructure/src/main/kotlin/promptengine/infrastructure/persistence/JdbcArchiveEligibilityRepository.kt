package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.prompt.ArchiveEligibility
import promptengine.domain.prompt.ArchiveEligibilityRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.sql.Timestamp
import java.time.Instant

/**
 * [ArchiveEligibilityRepository]のJDBC実装（Issue #48、ADR-0026決定5）。
 *
 * `prompt_versions.created_at`と`execution_logs`を1回の問い合わせで突き合わせる。
 * `PromptVersion` Aggregateは`created_at`を公開していないが、判定のためだけに
 * ドメインモデルへ永続化メタデータを持ち込むのは避け、SQL側でだけ参照する。
 */
class JdbcArchiveEligibilityRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : ArchiveEligibilityRepository {
    private data class Row(val preCutover: Boolean, val recentlyExecuted: Boolean)

    override fun evaluate(
        key: PromptKey,
        semVer: SemVer,
        cutoverAt: Instant,
        inactiveSince: Instant,
    ): ArchiveEligibility {
        val row =
            jdbcTemplate.query(
                """
                SELECT
                    pv.created_at < :cutoverAt AS pre_cutover,
                    EXISTS (
                        SELECT 1 FROM execution_logs el
                        WHERE el.version_id = pv.version_id AND el.executed_at >= :inactiveSince
                    ) AS recently_executed
                FROM prompt_versions pv
                JOIN prompts p ON p.prompt_id = pv.prompt_id
                WHERE p.prompt_key = :promptKey AND pv.version = :version
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("promptKey", key.value)
                    .addValue("version", semVer.toString())
                    .addValue("cutoverAt", Timestamp.from(cutoverAt))
                    .addValue("inactiveSince", Timestamp.from(inactiveSince)),
            ) { rs, _ -> Row(rs.getBoolean("pre_cutover"), rs.getBoolean("recently_executed")) }
                .singleOrNull()

        return when {
            row == null -> ArchiveEligibility.VersionNotFound
            // カットオーバー以前のVersionは「実行記録が無い」ことから参照ゼロを結論できない。
            // execution_logsの内容に関わらず判断不能として扱う（ADR-0026決定5）。
            row.preCutover -> ArchiveEligibility.PreCutover
            row.recentlyExecuted -> ArchiveEligibility.RecentlyExecuted
            else -> ArchiveEligibility.Inactive
        }
    }
}
