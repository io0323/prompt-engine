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
 *
 * [replaceOutbound]（P9bで追加）が本番の書き込み経路であり、`DependencyKind.name`
 * （大文字）で統一して書き込む。過去に存在した小文字（`"template"`等）のテストフィクスチャとの
 * casing不一致対策として、読み取り側（[findOutbound]/[findInbound]）は引き続き
 * `UPPER(d.to_kind)`で大文字小文字を無視して比較する。
 *
 * M2-3（ADR-0033）で[replaceOutbound]の呼び出し元（`CreatePromptHandler`/`CreateVersionHandler`）が
 * `CompositionService.compile`（COMPILE_ONLY）ベースへ変わり、extends由来のTEMPLATE依存に加え
 * import/include（FRAGMENT）由来の依存も書き込まれるようになった。Nested Prompt
 * （PROMPT、Issue #19未実装）由来の依存は引き続き対象外。
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

    override fun findOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
    ): List<DependencyEdge> {
        val versionId = findVersionId(promptKey, semVer) ?: return emptyList()
        return jdbcTemplate.query(
            """
            SELECT d.to_kind, d.to_key, d.to_version
            FROM dependencies d
            WHERE d.from_version_id = :versionId
            """.trimIndent(),
            MapSqlParameterSource().addValue("versionId", versionId),
        ) { rs, _ ->
            DependencyEdge(
                fromKey = promptKey,
                fromVersion = semVer,
                toKind = DependencyKind.valueOf(rs.getString("to_kind").uppercase()),
                toKey = rs.getString("to_key"),
                toVersion = rs.getString("to_version"),
            )
        }
    }

    override fun replaceOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
        edges: List<DependencyEdge>,
    ) {
        val versionId =
            findVersionId(promptKey, semVer)
                ?: error("no prompt_versions row for ${promptKey.value}@$semVer")
        jdbcTemplate.update(
            "DELETE FROM dependencies WHERE from_version_id = :versionId",
            MapSqlParameterSource().addValue("versionId", versionId),
        )
        edges.forEach { edge ->
            jdbcTemplate.update(
                """
                INSERT INTO dependencies (from_version_id, to_kind, to_key, to_version)
                VALUES (:versionId, :toKind, :toKey, :toVersion)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("versionId", versionId)
                    .addValue("toKind", edge.toKind.name)
                    .addValue("toKey", edge.toKey)
                    .addValue("toVersion", edge.toVersion),
            )
        }
    }

    private fun findVersionId(
        promptKey: PromptKey,
        semVer: SemVer,
    ): UUID? =
        jdbcTemplate
            .query(
                """
                SELECT pv.version_id
                FROM prompt_versions pv
                JOIN prompts p ON p.prompt_id = pv.prompt_id
                WHERE p.prompt_key = :promptKey AND pv.version = :version
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("promptKey", promptKey.value)
                    .addValue("version", semVer.toString()),
            ) { rs, _ -> rs.getObject("version_id", UUID::class.java) }
            .firstOrNull()

    override fun findInbound(promptKey: PromptKey): List<DependencyEdge> =
        jdbcTemplate.query(
            """
            SELECT p.prompt_key AS from_key, pv.version AS from_version, d.to_kind, d.to_key, d.to_version
            FROM dependencies d
            JOIN prompt_versions pv ON pv.version_id = d.from_version_id
            JOIN prompts p ON p.prompt_id = pv.prompt_id
            WHERE UPPER(d.to_kind) = 'PROMPT' AND d.to_key = :promptKey
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

    override fun findInboundTemplateOrFragment(
        kind: DependencyKind,
        key: String,
    ): List<DependencyEdge> =
        jdbcTemplate.query(
            """
            SELECT p.prompt_key AS from_key, pv.version AS from_version, d.to_kind, d.to_key, d.to_version
            FROM dependencies d
            JOIN prompt_versions pv ON pv.version_id = d.from_version_id
            JOIN prompts p ON p.prompt_id = pv.prompt_id
            WHERE UPPER(d.to_kind) = :kind AND d.to_key = :key
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("kind", kind.name)
                .addValue("key", key),
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
