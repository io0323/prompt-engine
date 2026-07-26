package promptengine.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.context.ContextRequirement
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PersistenceApi
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptDomainEvent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMemento
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionMemento
import promptengine.domain.variable.VariableDefinition
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
 */
@Suppress("TooManyFunctions")
@Repository
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

            val versionMementos =
                jdbcTemplate.query(
                    """
                    SELECT version_id, version, content, status, context_requirement
                    FROM prompt_versions WHERE prompt_id = :promptId ORDER BY created_at
                    """.trimIndent(),
                    MapSqlParameterSource("promptId", promptRow.promptId),
                ) { rs, _ ->
                    val versionId = rs.getObject("version_id", UUID::class.java)
                    PromptVersionMemento(
                        semVer = parseSemVer(rs.getString("version")),
                        content = PromptContent(rs.getString("content")),
                        variables = loadVariables(versionId),
                        contextRequirement = rs.getString("context_requirement")?.let { readContextRequirement(it) },
                        state = lifecycleStateFromDbValue(rs.getString("status")),
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

            val promptId = upsertPrompt(prompt, actor, occurredAt)
            prompt.versions.forEach { version -> upsertVersion(promptId, version, actor, occurredAt) }

            if (events.isNotEmpty()) {
                appendEvents(promptId, events)
                maybeSnapshot(promptId, prompt)
            }

            withRowVersion(prompt, prompt.rowVersion + 1)
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
                    version.contextRequirement,
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

    private fun loadVariables(versionId: UUID): List<VariableDefinition> =
        jdbcTemplate.query(
            """
            SELECT name, type, required, default_value, constraints, sensitive
            FROM variable_defs WHERE version_id = :versionId
            """.trimIndent(),
            MapSqlParameterSource("versionId", versionId),
        ) { rs, _ ->
            VariableDefinition(
                name = rs.getString("name"),
                type = VariableType.valueOf(rs.getString("type")),
                required = rs.getBoolean("required"),
                default = rs.getString("default_value")?.let { objectMapper.readValue(it, Any::class.java) },
                constraints =
                    rs.getString("constraints")?.let {
                        objectMapper.readValue(it, object : TypeReference<List<String>>() {})
                    } ?: emptyList(),
                sensitive = rs.getBoolean("sensitive"),
            )
        }

    private fun readContextRequirement(json: String): ContextRequirement =
        objectMapper.readValue(json, ContextRequirement::class.java)

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

    private fun upsertPrompt(
        prompt: Prompt,
        actor: String,
        occurredAt: Instant,
    ): UUID {
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
            return promptId
        }

        if (existing.rowVersion != prompt.rowVersion) {
            throw VersionConflictException(prompt.key, prompt.rowVersion, existing.rowVersion)
        }
        val updatedRows =
            jdbcTemplate.update(
                """
                UPDATE prompts SET name = :name, state = :state, row_version = row_version + 1, updated_at = :updatedAt
                WHERE prompt_id = :promptId AND row_version = :expectedRowVersion
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("name", prompt.key.name)
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
        return existing.promptId
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
                    (version_id, prompt_id, version, content, content_hash, status, context_requirement, created_by, created_at)
                VALUES
                    (:versionId, :promptId, :version, :content, :contentHash, :status, :contextRequirement::json, :createdBy, :createdAt)
                ON CONFLICT (prompt_id, version) DO UPDATE SET
                    content = EXCLUDED.content,
                    content_hash = EXCLUDED.content_hash,
                    status = EXCLUDED.status,
                    context_requirement = EXCLUDED.context_requirement
                RETURNING version_id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("versionId", UUID.randomUUID())
                    .addValue("promptId", promptId)
                    .addValue("version", version.semVer.toString())
                    .addValue("content", version.content.source)
                    .addValue("contentHash", version.content.contentHash)
                    .addValue("status", version.state.toDbValue())
                    .addValue(
                        "contextRequirement",
                        version.contextRequirement?.let { objectMapper.writeValueAsString(it) },
                    ).addValue("createdBy", actor)
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
                    .addValue("required", variable.required)
                    .addValue("defaultValue", variable.default?.let { objectMapper.writeValueAsString(it) })
                    .addValue("constraints", objectMapper.writeValueAsString(variable.constraints))
                    .addValue("sensitive", variable.sensitive)
            }.toTypedArray()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO variable_defs (variable_id, version_id, name, type, required, default_value, constraints, sensitive)
            VALUES (:variableId, :versionId, :name, :type, :required, :defaultValue, :constraints::json, :sensitive)
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
            val eventId = UUID.randomUUID()
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
    }
}
