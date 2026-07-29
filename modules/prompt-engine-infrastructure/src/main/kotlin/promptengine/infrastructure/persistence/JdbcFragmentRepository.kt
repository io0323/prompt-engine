package promptengine.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentContent
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentMemento
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.fragment.FragmentVersion
import promptengine.domain.fragment.FragmentVersionMemento
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * [FragmentRepository] のJDBC実装（設計書§3.4・ADR-0008）。[JdbcTemplateRepository] と
 * 対称の実装。Domain Event/Outbox/Snapshotへの追記は行わない（ADR-0008）。
 */
class JdbcFragmentRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : FragmentRepository {
    @OptIn(PersistenceApi::class)
    override fun findByKey(key: FragmentKey): Fragment? =
        transactionTemplate.execute {
            val fragmentRow = findFragmentRow(key) ?: return@execute null

            data class VersionRow(
                val versionId: UUID,
                val semVer: String,
                val body: String,
                val status: String,
            )

            val versionRows =
                jdbcTemplate.query(
                    """
                    SELECT version_id, version, body, status
                    FROM fragment_versions WHERE fragment_id = :fragmentId ORDER BY created_at, version_id
                    """.trimIndent(),
                    MapSqlParameterSource("fragmentId", fragmentRow.fragmentId),
                ) { rs, _ ->
                    VersionRow(
                        versionId = rs.getObject("version_id", UUID::class.java),
                        semVer = rs.getString("version"),
                        body = rs.getString("body"),
                        status = rs.getString("status"),
                    )
                }

            val variablesByVersionId = loadVariablesByVersionIds(versionRows.map { it.versionId })

            val versionMementos =
                versionRows.map { row ->
                    FragmentVersionMemento(
                        semVer = parseSemVer(row.semVer),
                        content = FragmentContent(row.body),
                        variables = variablesByVersionId[row.versionId] ?: emptyList(),
                        state = publicationStateFromDbValue(row.status),
                    )
                }

            Fragment.restore(FragmentMemento(key, versionMementos, fragmentRow.rowVersion))
        }

    @OptIn(PersistenceApi::class)
    override fun save(fragment: Fragment): Fragment =
        transactionTemplate.execute {
            val (fragmentId, newRowVersion) = upsertFragment(fragment)
            fragment.versions.forEach { version -> upsertVersion(fragmentId, version) }
            withRowVersion(fragment, newRowVersion)
        } ?: error("save transaction returned null")

    @OptIn(PersistenceApi::class)
    private fun withRowVersion(
        fragment: Fragment,
        rowVersion: Long,
    ): Fragment {
        val versionMementos =
            fragment.versions.map {
                FragmentVersionMemento(it.semVer, it.content, it.variables, it.state)
            }
        return Fragment.restore(FragmentMemento(fragment.key, versionMementos, rowVersion))
    }

    private fun findFragmentRow(key: FragmentKey): FragmentRow? =
        jdbcTemplate.query(
            "SELECT fragment_id, row_version FROM fragments WHERE fragment_key = :fragmentKey",
            MapSqlParameterSource("fragmentKey", key.value),
        ) { rs, _ -> FragmentRow(rs.getObject("fragment_id", UUID::class.java), rs.getLong("row_version")) }
            .firstOrNull()

    /** Version数ぶんのN+1クエリを避けるため、複数versionIdをまとめて1回のIN句で取得する。 */
    private fun loadVariablesByVersionIds(versionIds: List<UUID>): Map<UUID, List<VariableDefinition>> =
        if (versionIds.isEmpty()) {
            emptyMap()
        } else {
            jdbcTemplate.query(
                """
                SELECT version_id, name, type, source, required, default_value, constraints, sensitive
                FROM fragment_variable_defs WHERE version_id IN (:versionIds)
                """.trimIndent(),
                MapSqlParameterSource("versionIds", versionIds),
            ) { rs, _ ->
                rs.getObject("version_id", UUID::class.java) to
                    VariableDefinition(
                        name = rs.getString("name"),
                        type = VariableType.valueOf(rs.getString("type")),
                        source = VariableSource.valueOf(rs.getString("source")),
                        required = rs.getBoolean("required"),
                        default = rs.getString("default_value")?.let { objectMapper.readValue(it, Any::class.java) },
                        constraints =
                            rs.getString("constraints")?.let {
                                objectMapper.readValue(it, object : TypeReference<List<String>>() {})
                            } ?: emptyList(),
                        sensitive = rs.getBoolean("sensitive"),
                    )
            }.groupBy({ it.first }, { it.second })
        }

    private fun upsertFragment(fragment: Fragment): Pair<UUID, Long> {
        val existing = findFragmentRow(fragment.key)
        val now = Timestamp.from(Instant.now())

        if (existing == null) {
            val fragmentId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO fragments (fragment_id, fragment_key, row_version, created_by, created_at, updated_at)
                VALUES (:fragmentId, :fragmentKey, 0, :createdBy, :createdAt, :updatedAt)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("fragmentId", fragmentId)
                    .addValue("fragmentKey", fragment.key.value)
                    .addValue("createdBy", DEFAULT_ACTOR)
                    .addValue("createdAt", now)
                    .addValue("updatedAt", now),
            )
            return fragmentId to INITIAL_ROW_VERSION
        }

        if (existing.rowVersion != fragment.rowVersion) {
            throw FragmentVersionConflictException(fragment.key, fragment.rowVersion, existing.rowVersion)
        }
        val updatedRows =
            jdbcTemplate.update(
                """
                UPDATE fragments SET row_version = row_version + 1, updated_at = :updatedAt
                WHERE fragment_id = :fragmentId AND row_version = :expectedRowVersion
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("updatedAt", now)
                    .addValue("fragmentId", existing.fragmentId)
                    .addValue("expectedRowVersion", fragment.rowVersion),
            )
        if (updatedRows == 0) {
            val currentRowVersion = findFragmentRow(fragment.key)?.rowVersion ?: existing.rowVersion
            throw FragmentVersionConflictException(fragment.key, fragment.rowVersion, currentRowVersion)
        }
        return existing.fragmentId to (fragment.rowVersion + 1)
    }

    private fun upsertVersion(
        fragmentId: UUID,
        version: FragmentVersion,
    ) {
        val versionId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO fragment_versions
                    (version_id, fragment_id, version, body, content_hash, status, created_by, created_at)
                VALUES
                    (:versionId, :fragmentId, :version, :body, :contentHash, :status, :createdBy, :createdAt)
                ON CONFLICT (fragment_id, version) DO UPDATE SET
                    body = EXCLUDED.body,
                    content_hash = EXCLUDED.content_hash,
                    status = EXCLUDED.status
                RETURNING version_id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("versionId", UUID.randomUUID())
                    .addValue("fragmentId", fragmentId)
                    .addValue("version", version.semVer.toString())
                    .addValue("body", version.content.source)
                    .addValue("contentHash", version.content.contentHash)
                    .addValue("status", version.state.toDbValue())
                    .addValue("createdBy", DEFAULT_ACTOR)
                    .addValue("createdAt", Timestamp.from(Instant.now())),
                UUID::class.java,
            )!!

        replaceVariables(versionId, version.variables)
    }

    private fun replaceVariables(
        versionId: UUID,
        variables: List<VariableDefinition>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM fragment_variable_defs WHERE version_id = :versionId",
            MapSqlParameterSource("versionId", versionId),
        )
        if (variables.isEmpty()) return

        val batchParams =
            variables.map { variable ->
                MapSqlParameterSource()
                    .addValue("variableId", UUID.randomUUID())
                    .addValue("versionId", versionId)
                    .addValue("name", variable.name)
                    .addValue("type", variable.type.name)
                    .addValue("source", variable.source.name)
                    .addValue("required", variable.required)
                    .addValue("defaultValue", variable.default?.let { objectMapper.writeValueAsString(it) })
                    .addValue("constraints", objectMapper.writeValueAsString(variable.constraints))
                    .addValue("sensitive", variable.sensitive)
            }.toTypedArray()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO fragment_variable_defs
                (variable_id, version_id, name, type, source, required, default_value, constraints, sensitive)
            VALUES
                (:variableId, :versionId, :name, :type, :source, :required, :defaultValue, :constraints::json, :sensitive)
            """.trimIndent(),
            batchParams,
        )
    }

    private data class FragmentRow(val fragmentId: UUID, val rowVersion: Long)

    private companion object {
        const val DEFAULT_ACTOR = "system"
        const val INITIAL_ROW_VERSION = 0L
    }
}
