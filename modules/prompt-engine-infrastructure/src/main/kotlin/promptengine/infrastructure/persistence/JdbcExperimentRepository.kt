package promptengine.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentDomainEvent
import promptengine.domain.experiment.ExperimentMemento
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.Variant
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.PersistenceApi
import java.util.UUID

/**
 * [ExperimentRepository]のJDBC実装（設計書§12 `experiments`/`variants`、ADR-0034）。
 *
 * `experiments`に`row_version`列が無いため、`ReviewCase`と同じ「読み取り時に
 * `SELECT ... FOR UPDATE`でロックする」方式を用いる（`JdbcReviewCaseRepository`のKDoc参照）。
 * `variants`は保存のたびにDELETE→再INSERTする（`approvals`と同じ方式。件数はVariant数
 * 程度で小さく性能上の懸念はない）。`variant_id`は[Variant]自身がドメイン側で採番
 * （`ExperimentVariantResolver`/コマンドハンドラが`UUID.randomUUID()`で発行）するため、
 * `review_id`と同様に単純なUPSERTで保存できる。
 */
class JdbcExperimentRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : ExperimentRepository {
    @OptIn(PersistenceApi::class)
    override fun findById(experimentId: UUID): Experiment? =
        transactionTemplate.execute {
            val row = findRow(experimentId) ?: return@execute null
            restore(row)
        }

    @OptIn(PersistenceApi::class)
    override fun findActiveByPrompt(promptKey: PromptKey): List<Experiment> =
        transactionTemplate.execute {
            val ids =
                jdbcTemplate.query(
                    """
                    SELECT e.experiment_id
                    FROM experiments e
                    JOIN prompts p ON p.prompt_id = e.prompt_id
                    WHERE p.prompt_key = :promptKey AND e.status = 'Running'
                    """.trimIndent(),
                    MapSqlParameterSource("promptKey", promptKey.value),
                ) { rs, _ -> rs.getObject("experiment_id", UUID::class.java) }
            ids.mapNotNull { findRow(it) }.map { restore(it) }
        } ?: emptyList()

    @OptIn(PersistenceApi::class)
    override fun save(
        experiment: Experiment,
        events: List<ExperimentDomainEvent>,
    ): Experiment =
        transactionTemplate.execute {
            upsertExperiment(experiment)
            replaceVariants(experiment.experimentId, experiment.variants)
            jdbcTemplate.appendExperimentDomainEvents(objectMapper, experiment.experimentId, events)
            experiment
        } ?: error("save transaction returned null")

    private fun findRow(experimentId: UUID): ExperimentRow? =
        jdbcTemplate.query(
            """
            SELECT e.experiment_id, p.prompt_key, e.type, e.status, e.winner_variant_id, e.sticky_key_path
            FROM experiments e
            JOIN prompts p ON p.prompt_id = e.prompt_id
            WHERE e.experiment_id = :experimentId
            FOR UPDATE OF e
            """.trimIndent(),
            MapSqlParameterSource("experimentId", experimentId),
        ) { rs, _ ->
            ExperimentRow(
                experimentId = rs.getObject("experiment_id", UUID::class.java),
                promptKey = rs.getString("prompt_key"),
                type = rs.getString("type"),
                status = rs.getString("status"),
                winnerVariantId = rs.getObject("winner_variant_id", UUID::class.java),
                stickyKeyPath = rs.getString("sticky_key_path"),
            )
        }.singleOrNull()

    @OptIn(PersistenceApi::class)
    private fun restore(row: ExperimentRow): Experiment =
        Experiment.restore(
            ExperimentMemento(
                experimentId = row.experimentId,
                promptKey = PromptKey(row.promptKey),
                type = experimentTypeFromDbValue(row.type),
                status = experimentStatusFromDbValue(row.status),
                variants = loadVariants(row.experimentId),
                trafficPolicy = TrafficPolicy(row.stickyKeyPath),
                winnerVariantId = row.winnerVariantId,
            ),
        )

    private fun loadVariants(experimentId: UUID): List<Variant> =
        jdbcTemplate.query(
            """
            SELECT v.variant_id, v.name, v.weight_pct, pv.version
            FROM variants v
            JOIN prompt_versions pv ON pv.version_id = v.version_id
            WHERE v.experiment_id = :experimentId
            ORDER BY v.name
            """.trimIndent(),
            MapSqlParameterSource("experimentId", experimentId),
        ) { rs, _ ->
            Variant(
                variantId = rs.getObject("variant_id", UUID::class.java),
                name = rs.getString("name"),
                promptVersionSemVer = parseSemVer(rs.getString("version")),
                weightPct = rs.getInt("weight_pct"),
            )
        }

    private fun resolvePromptId(promptKey: PromptKey): UUID =
        jdbcTemplate.queryForObject(
            "SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey",
            MapSqlParameterSource("promptKey", promptKey.value),
            UUID::class.java,
        ) ?: error("Prompt not found for '${promptKey.value}'")

    private fun upsertExperiment(experiment: Experiment) {
        val promptId = resolvePromptId(experiment.promptKey)
        jdbcTemplate.update(
            """
            INSERT INTO experiments (experiment_id, prompt_id, type, status, started_at, winner_variant_id, sticky_key_path)
            VALUES (:experimentId, :promptId, :type, :status, now(), :winnerVariantId, :stickyKeyPath)
            ON CONFLICT (experiment_id) DO UPDATE SET
                status = EXCLUDED.status,
                winner_variant_id = EXCLUDED.winner_variant_id,
                ended_at = CASE WHEN EXCLUDED.status IN ('Stopped', 'Completed') THEN now() ELSE experiments.ended_at END
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("experimentId", experiment.experimentId)
                .addValue("promptId", promptId)
                .addValue("type", experiment.type.toDbValue())
                .addValue("status", experiment.status.toDbValue())
                .addValue("winnerVariantId", experiment.winnerVariantId)
                .addValue("stickyKeyPath", experiment.trafficPolicy.stickyKeyPath),
        )
    }

    private fun replaceVariants(
        experimentId: UUID,
        variants: List<Variant>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM variants WHERE experiment_id = :experimentId",
            MapSqlParameterSource("experimentId", experimentId),
        )
        if (variants.isEmpty()) return

        val promptKey = findPromptKeyForExperiment(experimentId)
        val batchParams =
            variants.map { variant ->
                val versionId =
                    jdbcTemplate.findVersionId(promptKey.value, variant.promptVersionSemVer)
                        ?: error(
                            "PromptVersion not found for '${promptKey.value}' version '${variant.promptVersionSemVer}'",
                        )
                MapSqlParameterSource()
                    .addValue("variantId", variant.variantId)
                    .addValue("experimentId", experimentId)
                    .addValue("versionId", versionId)
                    .addValue("name", variant.name)
                    .addValue("weightPct", variant.weightPct)
            }.toTypedArray()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO variants (variant_id, experiment_id, version_id, name, weight_pct)
            VALUES (:variantId, :experimentId, :versionId, :name, :weightPct)
            """.trimIndent(),
            batchParams,
        )
    }

    /**
     * [replaceVariants]は[upsertExperiment]の後に呼ばれるため、`experiments`行から
     * `prompt_key`を引き直せる（Experiment自身のフィールドを引数で受け渡すより、
     * 保存済みの行から解決する方が呼出順序に依存しない）。
     */
    private fun findPromptKeyForExperiment(experimentId: UUID): PromptKey =
        jdbcTemplate.queryForObject(
            """
            SELECT p.prompt_key FROM experiments e
            JOIN prompts p ON p.prompt_id = e.prompt_id
            WHERE e.experiment_id = :experimentId
            """.trimIndent(),
            MapSqlParameterSource("experimentId", experimentId),
            String::class.java,
        )?.let { PromptKey(it) } ?: error("Experiment not found: '$experimentId'")

    private data class ExperimentRow(
        val experimentId: UUID,
        val promptKey: String,
        val type: String,
        val status: String,
        val winnerVariantId: UUID?,
        val stickyKeyPath: String?,
    )
}
