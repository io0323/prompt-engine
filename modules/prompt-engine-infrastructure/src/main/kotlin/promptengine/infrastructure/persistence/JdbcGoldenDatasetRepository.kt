package promptengine.infrastructure.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.benchmark.GoldenDatasetMemento
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.PersistenceApi
import java.util.UUID

/**
 * [GoldenDatasetRepository]のJDBC実装（設計書§12 `golden_datasets`/`golden_dataset_items`、
 * ADR-0035）。
 *
 * `golden_datasets`に`row_version`列が無いため、`Experiment`と同様に同時更新は考慮しない
 * （単一の管理者操作を前提とする）。[GoldenDatasetItem.itemId]はドメイン側で採番される
 * （`Variant`と同じ）ため、`items`は保存のたびにDELETE→再INSERTする（`replaceVariants`と
 * 同じ方式）。挿入順は`position`列で保持する（DELETE+再INSERTでは`created_at`が
 * ほぼ同時刻になり順序を保証できないため）。
 */
class JdbcGoldenDatasetRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : GoldenDatasetRepository {
    @OptIn(PersistenceApi::class)
    override fun findById(datasetId: UUID): GoldenDataset? =
        transactionTemplate.execute {
            val row = findRow(datasetId) ?: return@execute null
            restore(row)
        }

    @OptIn(PersistenceApi::class)
    override fun findByPromptKey(promptKey: PromptKey): List<GoldenDataset> =
        transactionTemplate.execute {
            val ids =
                jdbcTemplate.query(
                    """
                    SELECT gd.dataset_id
                    FROM golden_datasets gd
                    JOIN prompts p ON p.prompt_id = gd.prompt_id
                    WHERE p.prompt_key = :promptKey
                    ORDER BY gd.created_at
                    """.trimIndent(),
                    MapSqlParameterSource("promptKey", promptKey.value),
                ) { rs, _ -> rs.getObject("dataset_id", UUID::class.java) }
            ids.mapNotNull { findRow(it) }.map { restore(it) }
        } ?: emptyList()

    @OptIn(PersistenceApi::class)
    override fun save(dataset: GoldenDataset): GoldenDataset =
        transactionTemplate.execute {
            upsertDataset(dataset)
            replaceItems(dataset.datasetId, dataset.items)
            dataset
        } ?: error("save transaction returned null")

    private fun findRow(datasetId: UUID): DatasetRow? =
        jdbcTemplate.query(
            """
            SELECT gd.dataset_id, p.prompt_key, gd.name, gd.description
            FROM golden_datasets gd
            JOIN prompts p ON p.prompt_id = gd.prompt_id
            WHERE gd.dataset_id = :datasetId
            """.trimIndent(),
            MapSqlParameterSource("datasetId", datasetId),
        ) { rs, _ ->
            DatasetRow(
                datasetId = rs.getObject("dataset_id", UUID::class.java),
                promptKey = rs.getString("prompt_key"),
                name = rs.getString("name"),
                description = rs.getString("description"),
            )
        }.singleOrNull()

    @OptIn(PersistenceApi::class)
    private fun restore(row: DatasetRow): GoldenDataset =
        GoldenDataset.restore(
            GoldenDatasetMemento(
                datasetId = row.datasetId,
                promptKey = PromptKey(row.promptKey),
                name = row.name,
                description = row.description,
                items = loadItems(row.datasetId),
            ),
        )

    private fun loadItems(datasetId: UUID): List<GoldenDatasetItem> =
        jdbcTemplate.query(
            """
            SELECT item_id, parameters, context, expected_output, metadata
            FROM golden_dataset_items
            WHERE dataset_id = :datasetId
            ORDER BY position
            """.trimIndent(),
            MapSqlParameterSource("datasetId", datasetId),
        ) { rs, _ ->
            GoldenDatasetItem(
                itemId = rs.getObject("item_id", UUID::class.java),
                parameters = objectMapper.readValue(rs.getString("parameters"), PARAMETERS_TYPE),
                context = objectMapper.readValue(rs.getString("context"), CONTEXT_TYPE),
                expectedOutput = rs.getString("expected_output"),
                metadata =
                    rs.getString("metadata")?.let { objectMapper.readValue(it, METADATA_TYPE) }
                        ?: emptyMap(),
            )
        }

    private fun resolvePromptId(promptKey: PromptKey): UUID =
        jdbcTemplate.queryForObject(
            "SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey",
            MapSqlParameterSource("promptKey", promptKey.value),
            UUID::class.java,
        ) ?: error("Prompt not found for '${promptKey.value}'")

    private fun upsertDataset(dataset: GoldenDataset) {
        val promptId = resolvePromptId(dataset.promptKey)
        jdbcTemplate.update(
            """
            INSERT INTO golden_datasets (dataset_id, prompt_id, name, description)
            VALUES (:datasetId, :promptId, :name, :description)
            ON CONFLICT (dataset_id) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("datasetId", dataset.datasetId)
                .addValue("promptId", promptId)
                .addValue("name", dataset.name)
                .addValue("description", dataset.description),
        )
    }

    private fun replaceItems(
        datasetId: UUID,
        items: List<GoldenDatasetItem>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM golden_dataset_items WHERE dataset_id = :datasetId",
            MapSqlParameterSource("datasetId", datasetId),
        )
        if (items.isEmpty()) return

        val batchParams =
            items.mapIndexed { index, item ->
                MapSqlParameterSource()
                    .addValue("itemId", item.itemId)
                    .addValue("datasetId", datasetId)
                    .addValue("position", index)
                    .addValue("parameters", objectMapper.writeValueAsString(item.parameters))
                    .addValue("context", objectMapper.writeValueAsString(item.context))
                    .addValue("expectedOutput", item.expectedOutput)
                    .addValue(
                        "metadata",
                        if (item.metadata.isEmpty()) null else objectMapper.writeValueAsString(item.metadata),
                    )
            }.toTypedArray()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO golden_dataset_items
                (item_id, dataset_id, position, parameters, context, expected_output, metadata)
            VALUES
                (:itemId, :datasetId, :position, :parameters::jsonb, :context::jsonb, :expectedOutput, :metadata::jsonb)
            """.trimIndent(),
            batchParams,
        )
    }

    private data class DatasetRow(
        val datasetId: UUID,
        val promptKey: String,
        val name: String,
        val description: String?,
    )

    private companion object {
        val PARAMETERS_TYPE = object : TypeReference<Map<String, Any?>>() {}
        val CONTEXT_TYPE = object : TypeReference<Map<String, Map<String, Any?>>>() {}
        val METADATA_TYPE = object : TypeReference<Map<String, String>>() {}
    }
}
