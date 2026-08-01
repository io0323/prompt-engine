package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * [DependencyRepository]のJDBC実装（`dependencies`テーブル、設計書§12、ADR-0017）。
 */
class JdbcDependencyRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : DependencyRepository {
    override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> {
        val versionRow = findRepresentativeVersion(promptKey) ?: return emptyList()
        return jdbcTemplate.query(
            """
            SELECT d.to_kind, d.to_key, d.to_version
            FROM dependencies d
            WHERE d.from_version_id = :versionId
            """.trimIndent(),
            MapSqlParameterSource().addValue("versionId", versionRow.versionId),
        ) { rs, _ ->
            DependencyEdge(
                fromKey = promptKey,
                fromVersion = versionRow.semVer,
                toKind = DependencyKind.valueOf(rs.getString("to_kind").uppercase()),
                toKey = rs.getString("to_key"),
                toVersion = rs.getString("to_version"),
            )
        }
    }

    override fun findInbound(promptKey: PromptKey): List<DependencyEdge> =
        jdbcTemplate.query(
            """
            SELECT p.prompt_key AS from_key, pv.version AS from_version, d.to_kind, d.to_key, d.to_version
            FROM dependencies d
            JOIN prompt_versions pv ON pv.version_id = d.from_version_id
            JOIN prompts p ON p.prompt_id = pv.prompt_id
            WHERE d.to_kind = 'PROMPT' AND d.to_key = :promptKey
            """.trimIndent(),
            MapSqlParameterSource().addValue("promptKey", promptKey.value),
        ) { rs, _ ->
            DependencyEdge(
                fromKey = PromptKey(rs.getString("from_key")),
                fromVersion = parseSemVer(rs.getString("from_version")),
                toKind = DependencyKind.valueOf(rs.getString("to_kind").uppercase()),
                toKey = rs.getString("to_key"),
                toVersion = rs.getString("to_version"),
            )
        }

    /** [promptKey]のPublished Versionを優先し、無ければ最新（作成日時降順）のVersionを返す。 */
    private fun findRepresentativeVersion(promptKey: PromptKey): VersionRow? {
        val rows =
            jdbcTemplate.query(
                """
                SELECT pv.version_id, pv.version, pv.status
                FROM prompt_versions pv
                JOIN prompts p ON p.prompt_id = pv.prompt_id
                WHERE p.prompt_key = :promptKey
                ORDER BY pv.created_at DESC
                """.trimIndent(),
                MapSqlParameterSource().addValue("promptKey", promptKey.value),
            ) { rs, _ ->
                VersionRow(
                    versionId = rs.getObject("version_id", UUID::class.java),
                    semVer = parseSemVer(rs.getString("version")),
                    status = rs.getString("status"),
                )
            }
        return rows.find { it.status == "Published" } ?: rows.firstOrNull()
    }

    private data class VersionRow(val versionId: UUID, val semVer: SemVer, val status: String)
}
