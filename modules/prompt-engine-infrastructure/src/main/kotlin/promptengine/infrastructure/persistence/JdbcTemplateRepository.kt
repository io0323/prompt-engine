package promptengine.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateContent
import promptengine.domain.template.TemplateDomainEvent
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateMemento
import promptengine.domain.template.TemplateRepository
import promptengine.domain.template.TemplateVersion
import promptengine.domain.template.TemplateVersionMemento
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * [TemplateRepository] のJDBC実装（設計書§3.4・ADR-0008、イベント追記はADR-0033）。
 *
 * [EventStorePromptRepository.save]と同じ形（`events.firstOrNull()`からactor/occurredAtを
 * 導出し、`created_by`/`created_at`へ使う。イベントを`domain_events`/`outbox`へ追記する）に
 * 揃えた。Snapshot（`prompt_snapshots`相当）は本Aggregateには無い（ADR-0006のSnapshot最適化は
 * Prompt固有のスコープ、Template/Fragmentはversions件数が少なく必要性が薄いため対象外）。
 */
class JdbcTemplateRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : TemplateRepository {
    @OptIn(PersistenceApi::class)
    override fun findByKey(key: TemplateKey): Template? =
        transactionTemplate.execute {
            val templateRow = findTemplateRow(key) ?: return@execute null

            data class VersionRow(
                val versionId: UUID,
                val semVer: String,
                val body: String,
                val status: String,
                val extendsKey: String?,
                val extendsVersionRange: String?,
            )

            val versionRows =
                jdbcTemplate.query(
                    """
                    SELECT version_id, version, body, status, extends_key, extends_version_range
                    FROM template_versions WHERE template_id = :templateId ORDER BY created_at, version_id
                    """.trimIndent(),
                    MapSqlParameterSource("templateId", templateRow.templateId),
                ) { rs, _ ->
                    VersionRow(
                        versionId = rs.getObject("version_id", UUID::class.java),
                        semVer = rs.getString("version"),
                        body = rs.getString("body"),
                        status = rs.getString("status"),
                        extendsKey = rs.getString("extends_key"),
                        extendsVersionRange = rs.getString("extends_version_range"),
                    )
                }

            val variablesByVersionId = loadVariablesByVersionIds(versionRows.map { it.versionId })

            val versionMementos =
                versionRows.map { row ->
                    TemplateVersionMemento(
                        semVer = parseSemVer(row.semVer),
                        content = TemplateContent(row.body),
                        variables = variablesByVersionId[row.versionId] ?: emptyList(),
                        extends = extendsRefFromDbValue(row.extendsKey, row.extendsVersionRange),
                        state = publicationStateFromDbValue(row.status),
                    )
                }

            Template.restore(TemplateMemento(key, versionMementos, templateRow.rowVersion))
        }

    @OptIn(PersistenceApi::class)
    override fun save(
        template: Template,
        events: List<TemplateDomainEvent>,
    ): Template =
        transactionTemplate.execute {
            val actor = events.firstOrNull()?.actor ?: DEFAULT_ACTOR
            val occurredAt = events.firstOrNull()?.occurredAt ?: Instant.now()

            val (templateId, newRowVersion) = upsertTemplate(template, actor, occurredAt)
            template.versions.forEach { version -> upsertVersion(templateId, version, actor, occurredAt) }

            if (events.isNotEmpty()) {
                jdbcTemplate.appendTemplateDomainEvents(objectMapper, templateId, events)
            }

            withRowVersion(template, newRowVersion)
        } ?: error("save transaction returned null")

    @OptIn(PersistenceApi::class)
    private fun withRowVersion(
        template: Template,
        rowVersion: Long,
    ): Template {
        val versionMementos =
            template.versions.map {
                TemplateVersionMemento(it.semVer, it.content, it.variables, it.extends, it.state)
            }
        return Template.restore(TemplateMemento(template.key, versionMementos, rowVersion))
    }

    private fun findTemplateRow(key: TemplateKey): TemplateRow? =
        jdbcTemplate.query(
            "SELECT template_id, row_version FROM templates WHERE template_key = :templateKey",
            MapSqlParameterSource("templateKey", key.value),
        ) { rs, _ -> TemplateRow(rs.getObject("template_id", UUID::class.java), rs.getLong("row_version")) }
            .firstOrNull()

    /** Version数ぶんのN+1クエリを避けるため、複数versionIdをまとめて1回のIN句で取得する。 */
    private fun loadVariablesByVersionIds(versionIds: List<UUID>): Map<UUID, List<VariableDefinition>> =
        if (versionIds.isEmpty()) {
            emptyMap()
        } else {
            jdbcTemplate.query(
                """
                SELECT version_id, name, type, source, required, default_value, constraints, sensitive
                FROM template_variable_defs WHERE version_id IN (:versionIds)
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

    /**
     * `templates` 行をupsertし、`(template_id, 保存後のrow_version)` を返す。
     * INSERT時は `row_version=0` で作成するため `0` を、UPDATE時はDBが実際に
     * インクリメントした値（`template.rowVersion + 1`）を返す（EventStorePromptRepository
     * の `upsertPrompt` と同じ理由）。
     */
    private fun upsertTemplate(
        template: Template,
        actor: String,
        occurredAt: Instant,
    ): Pair<UUID, Long> {
        val existing = findTemplateRow(template.key)
        val now = Timestamp.from(occurredAt)

        if (existing == null) {
            val templateId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO templates (template_id, template_key, row_version, created_by, created_at, updated_at)
                VALUES (:templateId, :templateKey, 0, :createdBy, :createdAt, :updatedAt)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("templateId", templateId)
                    .addValue("templateKey", template.key.value)
                    .addValue("createdBy", actor)
                    .addValue("createdAt", now)
                    .addValue("updatedAt", now),
            )
            return templateId to INITIAL_ROW_VERSION
        }

        if (existing.rowVersion != template.rowVersion) {
            throw TemplateVersionConflictException(template.key, template.rowVersion, existing.rowVersion)
        }
        val updatedRows =
            jdbcTemplate.update(
                """
                UPDATE templates SET row_version = row_version + 1, updated_at = :updatedAt
                WHERE template_id = :templateId AND row_version = :expectedRowVersion
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("updatedAt", now)
                    .addValue("templateId", existing.templateId)
                    .addValue("expectedRowVersion", template.rowVersion),
            )
        if (updatedRows == 0) {
            // upsertTemplate冒頭のチェックとこのUPDATEの間に他トランザクションがcommitした場合。
            val currentRowVersion = findTemplateRow(template.key)?.rowVersion ?: existing.rowVersion
            throw TemplateVersionConflictException(template.key, template.rowVersion, currentRowVersion)
        }
        return existing.templateId to (template.rowVersion + 1)
    }

    private fun upsertVersion(
        templateId: UUID,
        version: TemplateVersion,
        actor: String,
        occurredAt: Instant,
    ) {
        val versionId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO template_versions
                    (version_id, template_id, version, body, content_hash, status,
                     extends_key, extends_version_range, created_by, created_at)
                VALUES
                    (:versionId, :templateId, :version, :body, :contentHash, :status,
                     :extendsKey, :extendsVersionRange, :createdBy, :createdAt)
                ON CONFLICT (template_id, version) DO UPDATE SET
                    body = EXCLUDED.body,
                    content_hash = EXCLUDED.content_hash,
                    status = EXCLUDED.status,
                    extends_key = EXCLUDED.extends_key,
                    extends_version_range = EXCLUDED.extends_version_range
                RETURNING version_id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("versionId", UUID.randomUUID())
                    .addValue("templateId", templateId)
                    .addValue("version", version.semVer.toString())
                    .addValue("body", version.content.source)
                    .addValue("contentHash", version.content.contentHash)
                    .addValue("status", version.state.toDbValue())
                    .addValue("extendsKey", version.extends.toExtendsKeyDbValue())
                    .addValue("extendsVersionRange", version.extends.toExtendsVersionRangeDbValue())
                    .addValue("createdBy", actor)
                    .addValue("createdAt", Timestamp.from(occurredAt)),
                UUID::class.java,
            )!!

        replaceVariables(versionId, version.variables)
    }

    private fun replaceVariables(
        versionId: UUID,
        variables: List<VariableDefinition>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM template_variable_defs WHERE version_id = :versionId",
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
            INSERT INTO template_variable_defs
                (variable_id, version_id, name, type, source, required, default_value, constraints, sensitive)
            VALUES
                (:variableId, :versionId, :name, :type, :source, :required, :defaultValue, :constraints::json, :sensitive)
            """.trimIndent(),
            batchParams,
        )
    }

    private data class TemplateRow(val templateId: UUID, val rowVersion: Long)

    private companion object {
        const val DEFAULT_ACTOR = "system"
        const val INITIAL_ROW_VERSION = 0L
    }
}
