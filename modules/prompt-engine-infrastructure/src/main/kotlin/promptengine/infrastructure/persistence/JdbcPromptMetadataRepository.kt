package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptMetadataRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import java.util.UUID

/**
 * [PromptMetadataRepository]のJDBC実装（`prompts`/`categories`/`tags`/`prompt_tags`テーブル、
 * 設計書§12、ADR-0020）。
 *
 * [upsert]は`prompts`行が既に存在する（`Prompt.create`＋`EventStorePromptRepository.save`が
 * 先に実行済みである）ことを前提とした`UPDATE`のみを行う（ADR-0020「両Repositoryが
 * 同じprompts テーブルの異なる列サブセットを独立に書く」設計）。`row_version`/`state`など
 * `EventStorePromptRepository`が管理する列には一切触れない。
 *
 * [upsert]は`prompts`の`UPDATE`・`prompt_tags`の`DELETE`+`INSERT`という複数文からなる。
 * 統一トランザクション無しでは`DELETE`後の失敗でタグが全消失しうるため、
 * [transactionTemplate]で1トランザクションに束ねる（CodeRabbitレビュー指摘）。
 */
class JdbcPromptMetadataRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : PromptMetadataRepository {
    private data class PromptMetadataRow(
        val promptId: UUID,
        val name: String,
        val category: String?,
        val description: String?,
    )

    override fun find(key: PromptKey): PromptMetadata? {
        val row =
            jdbcTemplate
                .query(
                    """
                    SELECT p.prompt_id, p.name, c.name AS category_name, p.description
                    FROM prompts p
                    LEFT JOIN categories c ON c.category_id = p.category_id
                    WHERE p.prompt_key = :promptKey
                    """.trimIndent(),
                    MapSqlParameterSource().addValue("promptKey", key.value),
                ) { rs, _ ->
                    PromptMetadataRow(
                        promptId = rs.getObject("prompt_id", UUID::class.java),
                        name = rs.getString("name"),
                        category = rs.getString("category_name"),
                        description = rs.getString("description"),
                    )
                }.singleOrNull() ?: return null

        return PromptMetadata(
            key = key,
            name = row.name,
            category = row.category,
            description = row.description,
            tags = findTags(row.promptId),
        )
    }

    override fun upsert(metadata: PromptMetadata) {
        transactionTemplate.executeWithoutResult {
            val promptId = findPromptId(metadata.key)
            val categoryId = metadata.category?.let { findOrCreateCategory(it) }
            jdbcTemplate.update(
                """
                UPDATE prompts SET name = :name, category_id = :categoryId, description = :description
                WHERE prompt_id = :promptId
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("name", metadata.name)
                    .addValue("categoryId", categoryId)
                    .addValue("description", metadata.description)
                    .addValue("promptId", promptId),
            )
            replaceTags(promptId, metadata.tags)
        }
    }

    private fun findTags(promptId: UUID): List<String> =
        jdbcTemplate.query(
            """
            SELECT t.name FROM tags t
            JOIN prompt_tags pt ON pt.tag_id = t.tag_id
            WHERE pt.prompt_id = :promptId
            ORDER BY t.name
            """.trimIndent(),
            MapSqlParameterSource().addValue("promptId", promptId),
        ) { rs, _ -> rs.getString("name") }

    private fun replaceTags(
        promptId: UUID,
        tags: List<String>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM prompt_tags WHERE prompt_id = :promptId",
            MapSqlParameterSource().addValue("promptId", promptId),
        )
        tags.forEach { tagName ->
            val tagId = findOrCreateTag(tagName)
            jdbcTemplate.update(
                "INSERT INTO prompt_tags (prompt_id, tag_id) VALUES (:promptId, :tagId) ON CONFLICT DO NOTHING",
                MapSqlParameterSource().addValue("promptId", promptId).addValue("tagId", tagId),
            )
        }
    }

    /**
     * `categories.name`のUNIQUE制約（V8）を前提に`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`で
     * 検索と作成を1文に束ね、SELECT-then-INSERTの競合（同名categoryの並行作成で一意制約違反や
     * 重複行が生じる）を避ける（CodeRabbitレビュー指摘）。
     */
    private fun findOrCreateCategory(name: String): UUID =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO categories (category_id, name) VALUES (:categoryId, :name)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            RETURNING category_id
            """.trimIndent(),
            MapSqlParameterSource().addValue("categoryId", UUID.randomUUID()).addValue("name", name),
            UUID::class.java,
        )!!

    /** `tags.name`はV1__init.sql時点で既にUNIQUEのため、[findOrCreateCategory]と同じ方式で束ねる。 */
    private fun findOrCreateTag(name: String): UUID =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO tags (tag_id, name) VALUES (:tagId, :name)
            ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
            RETURNING tag_id
            """.trimIndent(),
            MapSqlParameterSource().addValue("tagId", UUID.randomUUID()).addValue("name", name),
            UUID::class.java,
        )!!

    private fun findPromptId(key: PromptKey): UUID =
        jdbcTemplate
            .query(
                "SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey",
                MapSqlParameterSource().addValue("promptKey", key.value),
            ) { rs, _ -> rs.getObject("prompt_id", UUID::class.java) }
            .singleOrNull() ?: throw PromptVersionNotFoundException.forKey(key)
}
