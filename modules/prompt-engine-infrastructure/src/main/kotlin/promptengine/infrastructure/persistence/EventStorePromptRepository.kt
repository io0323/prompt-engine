package promptengine.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.context.ContextRequirement
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptDomainEvent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMemento
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionMemento
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.shared.PersistenceApi
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * [PromptRepository] のEvent Store付き実装（設計書§7クラス図・ADR-0006）。
 *
 * - `findByKey` はRDB投影（`prompts`/`prompt_versions`）から直接復元する
 *   （`domain_events`のリプレイは使わない。ADR-0006）。
 * - `save` は状態保存（`prompts`/`prompt_versions`）、Event Store追記
 *   （`domain_events`）、Outbox追記（`outbox`）を [transactionTemplate] で
 *   1トランザクションに束ねる。宣言的 `@Transactional` ではなく
 *   [TransactionTemplate] を使うのは、Springのプロキシ機構（DIコンテナ経由の
 *   呼び出し）に依存せず、テストからの直接インスタンス化でも正しく
 *   トランザクション境界が効くようにするため。
 * - 楽観ロックは `prompts.row_version` と [Prompt.rowVersion] を突き合わせ、
 *   不一致なら [VersionConflictException] を投げる（ADR-0006）。
 *
 * `prompts` / `prompt_versions`（`variable_defs`含む）/ `domain_events` / `outbox` /
 * `prompt_snapshots` という5テーブルにまたがる一つの永続化アダプタとしての
 * 責務がまとまっているため、detektの関数数閾値をわずかに超える。テーブル単位で
 * クラスを分割すると1トランザクション内の処理の見通しがかえって悪くなるため、
 * 恣意的な分割は行わない。
 *
 * `@Repository`等のステレオタイプアノテーションは付与しない。具象クラスのDI結線は
 * `prompt-engine-bootstrap` のConfigurationクラスの `@Bean` 定義でのみ行う
 * （CLAUDE.md「具象クラスのDI結線は prompt-engine-bootstrap のConfigurationクラスでのみ行う」）。
 */
@Suppress("TooManyFunctions")
class EventStorePromptRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val snapshotThreshold: Long = DEFAULT_SNAPSHOT_THRESHOLD,
) : PromptRepository {
    @OptIn(PersistenceApi::class)
    override fun findByKey(key: PromptKey): Prompt? =
        transactionTemplate.execute {
            val promptRow = findPromptRow(key) ?: return@execute null

            data class VersionRow(
                val versionId: UUID,
                val semVer: String,
                val content: String,
                val status: String,
                val contextRequirements: String?,
                val extendsKey: String?,
                val extendsVersionRange: String?,
                val validation: String?,
                val output: String?,
            )

            val versionRows =
                jdbcTemplate.query(
                    """
                    SELECT version_id, version, content, status, context_requirements,
                           extends_key, extends_version_range, validation, output
                    FROM prompt_versions WHERE prompt_id = :promptId ORDER BY created_at, version_id
                    """.trimIndent(),
                    MapSqlParameterSource("promptId", promptRow.promptId),
                ) { rs, _ ->
                    VersionRow(
                        versionId = rs.getObject("version_id", UUID::class.java),
                        semVer = rs.getString("version"),
                        content = rs.getString("content"),
                        status = rs.getString("status"),
                        contextRequirements = rs.getString("context_requirements"),
                        extendsKey = rs.getString("extends_key"),
                        extendsVersionRange = rs.getString("extends_version_range"),
                        validation = rs.getString("validation"),
                        output = rs.getString("output"),
                    )
                }

            // Version数ぶんのN+1クエリを避けるため、variable_defsは全Versionまとめて1回で取得する。
            val variablesByVersionId = loadVariablesByVersionIds(versionRows.map { it.versionId })

            val versionMementos =
                versionRows.map { row ->
                    PromptVersionMemento(
                        semVer = parseSemVer(row.semVer),
                        content = PromptContent(row.content),
                        variables = variablesByVersionId[row.versionId] ?: emptyList(),
                        contextRequirements =
                            row.contextRequirements?.let { readContextRequirements(it) } ?: emptyList(),
                        extends = extendsRefFromDbValue(row.extendsKey, row.extendsVersionRange),
                        validation = row.validation?.let { readValidationSettings(it) } ?: ValidationSettings(),
                        output = row.output?.let { readOutputDeclaration(it) },
                        state = lifecycleStateFromDbValue(row.status),
                    )
                }

            Prompt.restore(PromptMemento(key, versionMementos, promptRow.rowVersion))
        }

    @OptIn(PersistenceApi::class)
    override fun save(
        prompt: Prompt,
        events: List<PromptDomainEvent>,
    ): Prompt =
        transactionTemplate.execute {
            val actor = events.firstOrNull()?.actor ?: DEFAULT_ACTOR
            val occurredAt = events.firstOrNull()?.occurredAt ?: Instant.now()

            val (promptId, newRowVersion) = upsertPrompt(prompt, actor, occurredAt)
            prompt.versions.forEach { version -> upsertVersion(promptId, version, actor, occurredAt) }

            if (events.isNotEmpty()) {
                appendEvents(promptId, events)
                maybeSnapshot(promptId, prompt)
            }

            withRowVersion(prompt, newRowVersion)
        } ?: error("save transaction returned null")

    /**
     * `Prompt.copy()` は `@ConsistentCopyVisibility` によりinternalのため、
     * `rowVersion` だけを差し替えた新しい `Prompt` を作るにも [Prompt.restore] を
     * 経由する必要がある（ADR-0006）。
     */
    @OptIn(PersistenceApi::class)
    private fun withRowVersion(
        prompt: Prompt,
        rowVersion: Long,
    ): Prompt {
        val versionMementos =
            prompt.versions.map { version ->
                PromptVersionMemento(
                    version.semVer,
                    version.content,
                    version.variables,
                    version.contextRequirements,
                    version.extends,
                    version.validation,
                    version.output,
                    version.state,
                )
            }
        return Prompt.restore(PromptMemento(prompt.key, versionMementos, rowVersion))
    }

    private fun findPromptRow(key: PromptKey): PromptRow? =
        jdbcTemplate.query(
            "SELECT prompt_id, row_version FROM prompts WHERE prompt_key = :promptKey",
            MapSqlParameterSource("promptKey", key.value),
        ) { rs, _ -> PromptRow(rs.getObject("prompt_id", UUID::class.java), rs.getLong("row_version")) }
            .firstOrNull()

    /** Version数ぶんのN+1クエリを避けるため、複数versionIdをまとめて1回のIN句で取得する。 */
    private fun loadVariablesByVersionIds(versionIds: List<UUID>): Map<UUID, List<VariableDefinition>> =
        jdbcTemplate.query(
            """
            SELECT version_id, name, type, source, required, default_value, constraints, sensitive
            FROM variable_defs WHERE version_id IN (:versionIds)
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

    private fun readContextRequirements(json: String): List<ContextRequirement> =
        objectMapper.readValue(json, object : TypeReference<List<ContextRequirement>>() {})

    private fun readValidationSettings(json: String): ValidationSettings =
        objectMapper.readValue(json, ValidationSettings::class.java)

    private fun readOutputDeclaration(json: String): OutputDeclaration =
        objectMapper.readValue(json, OutputDeclaration::class.java)

    /**
     * `prompts.state` はQuery側の運用容易性のための非正規化投影であり（設計書§2.14）、
     * domain復元には使わない。Publishedが1件でもあれば"Published"、全Versionが
     * Archivedなら"Archived"、それ以外は最後に追加されたVersionの状態とする。
     */
    private fun projectedState(prompt: Prompt): String =
        when {
            prompt.versions.any { it.state == LifecycleState.Published } -> "Published"
            prompt.versions.all { it.state == LifecycleState.Archived } -> "Archived"
            else -> prompt.versions.last().state.toDbValue()
        }

    /**
     * `prompts` 行をupsertし、`(prompt_id, 保存後のrow_version)` を返す。
     * INSERT時は `row_version=0` で作成するため `0` を、UPDATE時はDBが実際に
     * インクリメントした値（`prompt.rowVersion + 1`）を返す ── `save` 側で
     * 無条件に `prompt.rowVersion + 1` を仮定すると、INSERT直後は実際のDB値（`0`）と
     * 食い違い、次のsave呼び出しが誤ってVERSION_CONFLICTになる。
     *
     * `name` 列はINSERT時のみ `prompt.key.name` を初期値として書き込み、UPDATE時は
     * 触らない（ADR-0020）。UPDATE文でも書き込むと、`submitForReview`/`approve`/`publish`
     * 等の状態遷移で毎回 `Prompt.save` が呼ばれるたび、`PromptMetadataRepository.upsert`
     * が設定したカスタム表示名が `prompt.key.name` で上書きされ失われてしまう。
     */
    private fun upsertPrompt(
        prompt: Prompt,
        actor: String,
        occurredAt: Instant,
    ): Pair<UUID, Long> {
        val existing = findPromptRow(prompt.key)
        val state = projectedState(prompt)

        if (existing == null) {
            val promptId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO prompts (prompt_id, prompt_key, name, state, row_version, created_by, created_at, updated_at)
                VALUES (:promptId, :promptKey, :name, :state, 0, :createdBy, :createdAt, :updatedAt)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("promptId", promptId)
                    .addValue("promptKey", prompt.key.value)
                    .addValue("name", prompt.key.name)
                    .addValue("state", state)
                    .addValue("createdBy", actor)
                    .addValue("createdAt", Timestamp.from(occurredAt))
                    .addValue("updatedAt", Timestamp.from(occurredAt)),
            )
            return promptId to INITIAL_ROW_VERSION
        }

        if (existing.rowVersion != prompt.rowVersion) {
            throw VersionConflictException(prompt.key, prompt.rowVersion, existing.rowVersion)
        }
        val updatedRows =
            jdbcTemplate.update(
                """
                UPDATE prompts SET state = :state, row_version = row_version + 1, updated_at = :updatedAt
                WHERE prompt_id = :promptId AND row_version = :expectedRowVersion
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("state", state)
                    .addValue("updatedAt", Timestamp.from(occurredAt))
                    .addValue("promptId", existing.promptId)
                    .addValue("expectedRowVersion", prompt.rowVersion),
            )
        if (updatedRows == 0) {
            // upsertPrompt冒頭のチェックとこのUPDATEの間に他トランザクションがcommitした場合。
            val currentRowVersion = findPromptRow(prompt.key)?.rowVersion ?: existing.rowVersion
            throw VersionConflictException(prompt.key, prompt.rowVersion, currentRowVersion)
        }
        return existing.promptId to (prompt.rowVersion + 1)
    }

    private fun upsertVersion(
        promptId: UUID,
        version: PromptVersion,
        actor: String,
        occurredAt: Instant,
    ) {
        val versionId =
            jdbcTemplate.queryForObject(
                """
                INSERT INTO prompt_versions
                    (version_id, prompt_id, version, content, content_hash, status, context_requirements,
                     extends_key, extends_version_range, validation, output, created_by, created_at)
                VALUES
                    (:versionId, :promptId, :version, :content, :contentHash, :status, :contextRequirements::json,
                     :extendsKey, :extendsVersionRange, :validation::json, :output::json, :createdBy, :createdAt)
                ON CONFLICT (prompt_id, version) DO UPDATE SET
                    content = EXCLUDED.content,
                    content_hash = EXCLUDED.content_hash,
                    status = EXCLUDED.status,
                    context_requirements = EXCLUDED.context_requirements,
                    extends_key = EXCLUDED.extends_key,
                    extends_version_range = EXCLUDED.extends_version_range,
                    validation = EXCLUDED.validation,
                    output = EXCLUDED.output
                RETURNING version_id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("versionId", UUID.randomUUID())
                    .addValue("promptId", promptId)
                    .addValue("version", version.semVer.toString())
                    .addValue("content", version.content.source)
                    .addValue("contentHash", version.content.contentHash)
                    .addValue("status", version.state.toDbValue())
                    .addValue("contextRequirements", objectMapper.writeValueAsString(version.contextRequirements))
                    .addValue("extendsKey", version.extends.toExtendsKeyDbValue())
                    .addValue("extendsVersionRange", version.extends.toExtendsVersionRangeDbValue())
                    .addValue("validation", objectMapper.writeValueAsString(version.validation))
                    .addValue("output", version.output?.let { objectMapper.writeValueAsString(it) })
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
            "DELETE FROM variable_defs WHERE version_id = :versionId",
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
            INSERT INTO variable_defs
                (variable_id, version_id, name, type, source, required, default_value, constraints, sensitive)
            VALUES
                (:variableId, :versionId, :name, :type, :source, :required, :defaultValue, :constraints::json, :sensitive)
            """.trimIndent(),
            batchParams,
        )
    }

    private fun currentMaxSequence(promptId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(sequence), 0) FROM domain_events WHERE aggregate_id = :promptId",
            MapSqlParameterSource("promptId", promptId),
            Long::class.java,
        )!!

    private fun appendEvents(
        promptId: UUID,
        events: List<PromptDomainEvent>,
    ) {
        val baseSequence = currentMaxSequence(promptId)
        events.forEachIndexed { index, event ->
            // event.eventIdはPrompt側で発行済み（例: Prompt.publish内のUUID.randomUUID()）。
            // ここで新規採番すると、リトライ時に同一イベントが別IDで重複登録されうる。
            val eventId = event.eventId
            jdbcTemplate.update(
                """
                INSERT INTO domain_events
                    (event_id, aggregate_type, aggregate_id, sequence, event_type, actor, trace_id, payload, occurred_at)
                VALUES
                    (:eventId, :aggregateType, :aggregateId, :sequence, :eventType, :actor, :traceId, :payload::json, :occurredAt)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("eventId", eventId)
                    .addValue("aggregateType", event.aggregateType)
                    .addValue("aggregateId", promptId)
                    .addValue("sequence", baseSequence + index + 1)
                    .addValue("eventType", event.eventType)
                    .addValue("actor", event.actor)
                    .addValue("traceId", event.traceId)
                    .addValue("payload", objectMapper.writeValueAsString(event.payload))
                    .addValue("occurredAt", Timestamp.from(event.occurredAt)),
            )
            jdbcTemplate.update(
                "INSERT INTO outbox (outbox_id, event_id, created_at) VALUES (:outboxId, :eventId, :createdAt)",
                MapSqlParameterSource()
                    .addValue("outboxId", UUID.randomUUID())
                    .addValue("eventId", eventId)
                    .addValue("createdAt", Timestamp.from(Instant.now())),
            )
        }
    }

    /** sequenceが直近スナップショットから [snapshotThreshold] 件を超えたらスナップショットを保存する（設計書§6.3）。 */
    private fun maybeSnapshot(
        promptId: UUID,
        prompt: Prompt,
    ) {
        val maxSequence = currentMaxSequence(promptId)
        val lastSnapshotSequence =
            jdbcTemplate.query(
                "SELECT sequence FROM prompt_snapshots WHERE aggregate_id = :promptId ORDER BY sequence DESC LIMIT 1",
                MapSqlParameterSource("promptId", promptId),
            ) { rs, _ -> rs.getLong("sequence") }.firstOrNull() ?: 0L

        if (maxSequence - lastSnapshotSequence < snapshotThreshold) return

        jdbcTemplate.update(
            """
            INSERT INTO prompt_snapshots (snapshot_id, aggregate_id, sequence, state, created_at)
            VALUES (:snapshotId, :aggregateId, :sequence, :state::jsonb, :createdAt)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("snapshotId", UUID.randomUUID())
                .addValue("aggregateId", promptId)
                .addValue("sequence", maxSequence)
                .addValue("state", objectMapper.writeValueAsString(PromptSnapshotPayload.from(prompt)))
                .addValue("createdAt", Timestamp.from(Instant.now())),
        )
    }

    private data class PromptRow(val promptId: UUID, val rowVersion: Long)

    private companion object {
        const val DEFAULT_ACTOR = "system"
        const val DEFAULT_SNAPSHOT_THRESHOLD = 50L
        const val INITIAL_ROW_VERSION = 0L
    }
}
