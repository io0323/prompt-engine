package promptengine.infrastructure.persistence

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkMemento
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.BenchmarkStatus
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.PersistenceApi
import java.util.UUID

/**
 * [BenchmarkRepository]のJDBC実装（設計書§12 `benchmarks`/`benchmark_targets`/
 * `benchmark_metrics`、ADR-0035）。
 *
 * `benchmarks`に`row_version`列が無いため、`Experiment`と同様に同時更新は考慮しない。
 * `targets`/`metrics`は保存のたびにDELETE→再INSERTする（`replaceVariants`と同じ方式）。
 *
 * `started_at`/`completed_at`/`cancelled_at`は`Experiment.started_at`（作成時に無条件で
 * `now()`）とは異なり、実際に該当する状態へ遷移した時点でのみ設定する（`Pending`は
 * 「まだ開始していない」ことを意味するため）。UPSERTのCASE式で、新しい`status`が
 * 該当状態でありまだ列が未設定の場合にのみ`now()`を書き込む。
 */
class JdbcBenchmarkRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : BenchmarkRepository {
    @OptIn(PersistenceApi::class)
    override fun findById(benchmarkId: UUID): Benchmark? =
        transactionTemplate.execute {
            val row = findRow(benchmarkId) ?: return@execute null
            restore(row)
        }

    @OptIn(PersistenceApi::class)
    override fun findByPromptKey(promptKey: PromptKey): List<Benchmark> =
        transactionTemplate.execute {
            val ids =
                jdbcTemplate.query(
                    """
                    SELECT b.benchmark_id
                    FROM benchmarks b
                    JOIN prompts p ON p.prompt_id = b.prompt_id
                    WHERE p.prompt_key = :promptKey
                    ORDER BY b.created_at
                    """.trimIndent(),
                    MapSqlParameterSource("promptKey", promptKey.value),
                ) { rs, _ -> rs.getObject("benchmark_id", UUID::class.java) }
            ids.mapNotNull { findRow(it) }.map { restore(it) }
        } ?: emptyList()

    @OptIn(PersistenceApi::class)
    override fun save(benchmark: Benchmark): Benchmark =
        transactionTemplate.execute {
            upsertBenchmark(benchmark)
            replaceTargets(benchmark.benchmarkId, benchmark.targets)
            replaceMetrics(benchmark.benchmarkId, benchmark.metrics)
            benchmark
        } ?: error("save transaction returned null")

    private fun findRow(benchmarkId: UUID): BenchmarkRow? =
        jdbcTemplate.query(
            """
            SELECT b.benchmark_id, p.prompt_key, b.dataset_id, b.n_repetitions, b.status
            FROM benchmarks b
            JOIN prompts p ON p.prompt_id = b.prompt_id
            WHERE b.benchmark_id = :benchmarkId
            """.trimIndent(),
            MapSqlParameterSource("benchmarkId", benchmarkId),
        ) { rs, _ ->
            BenchmarkRow(
                benchmarkId = rs.getObject("benchmark_id", UUID::class.java),
                promptKey = rs.getString("prompt_key"),
                datasetId = rs.getObject("dataset_id", UUID::class.java),
                nRepetitions = rs.getInt("n_repetitions"),
                status = rs.getString("status"),
            )
        }.singleOrNull()

    @OptIn(PersistenceApi::class)
    private fun restore(row: BenchmarkRow): Benchmark =
        Benchmark.restore(
            BenchmarkMemento(
                benchmarkId = row.benchmarkId,
                promptKey = PromptKey(row.promptKey),
                datasetId = row.datasetId,
                targets = loadTargets(row.benchmarkId),
                metrics = loadMetrics(row.benchmarkId),
                nRepetitions = row.nRepetitions,
                status = benchmarkStatusFromDbValue(row.status),
            ),
        )

    private fun loadTargets(benchmarkId: UUID): List<BenchmarkTarget> =
        jdbcTemplate.query(
            """
            SELECT t.target_id, pv.version
            FROM benchmark_targets t
            JOIN prompt_versions pv ON pv.version_id = t.version_id
            WHERE t.benchmark_id = :benchmarkId
            ORDER BY pv.version
            """.trimIndent(),
            MapSqlParameterSource("benchmarkId", benchmarkId),
        ) { rs, _ ->
            BenchmarkTarget(
                targetId = rs.getObject("target_id", UUID::class.java),
                promptVersionSemVer = parseSemVer(rs.getString("version")),
            )
        }

    private fun loadMetrics(benchmarkId: UUID): Set<BenchmarkMetricType> =
        jdbcTemplate.query(
            "SELECT metric_type FROM benchmark_metrics WHERE benchmark_id = :benchmarkId",
            MapSqlParameterSource("benchmarkId", benchmarkId),
        ) { rs, _ -> BenchmarkMetricType.valueOf(rs.getString("metric_type")) }.toSet()

    private fun upsertBenchmark(benchmark: Benchmark) {
        val promptId = jdbcTemplate.resolveBenchmarkPromptId(benchmark.promptKey)
        jdbcTemplate.update(
            """
            INSERT INTO benchmarks (benchmark_id, prompt_id, dataset_id, n_repetitions, status)
            VALUES (:benchmarkId, :promptId, :datasetId, :nRepetitions, :status)
            ON CONFLICT (benchmark_id) DO UPDATE SET
                status = EXCLUDED.status,
                started_at = CASE
                    WHEN EXCLUDED.status = 'Running' AND benchmarks.started_at IS NULL THEN now()
                    ELSE benchmarks.started_at
                END,
                completed_at = CASE
                    WHEN EXCLUDED.status = 'Completed' AND benchmarks.completed_at IS NULL THEN now()
                    ELSE benchmarks.completed_at
                END,
                cancelled_at = CASE
                    WHEN EXCLUDED.status = 'Cancelled' AND benchmarks.cancelled_at IS NULL THEN now()
                    ELSE benchmarks.cancelled_at
                END
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("benchmarkId", benchmark.benchmarkId)
                .addValue("promptId", promptId)
                .addValue("datasetId", benchmark.datasetId)
                .addValue("nRepetitions", benchmark.nRepetitions)
                .addValue("status", benchmark.status.toDbValue()),
        )
    }

    private fun replaceTargets(
        benchmarkId: UUID,
        targets: List<BenchmarkTarget>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM benchmark_targets WHERE benchmark_id = :benchmarkId",
            MapSqlParameterSource("benchmarkId", benchmarkId),
        )
        if (targets.isEmpty()) return

        val promptKey = jdbcTemplate.findPromptKeyForBenchmark(benchmarkId)
        val batchParams =
            targets.map { target ->
                val versionId =
                    jdbcTemplate.findVersionId(promptKey.value, target.promptVersionSemVer)
                        ?: error(
                            "PromptVersion not found for '${promptKey.value}' version '${target.promptVersionSemVer}'",
                        )
                MapSqlParameterSource()
                    .addValue("targetId", target.targetId)
                    .addValue("benchmarkId", benchmarkId)
                    .addValue("versionId", versionId)
            }.toTypedArray()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO benchmark_targets (target_id, benchmark_id, version_id)
            VALUES (:targetId, :benchmarkId, :versionId)
            """.trimIndent(),
            batchParams,
        )
    }

    private fun replaceMetrics(
        benchmarkId: UUID,
        metrics: Set<BenchmarkMetricType>,
    ) {
        jdbcTemplate.update(
            "DELETE FROM benchmark_metrics WHERE benchmark_id = :benchmarkId",
            MapSqlParameterSource("benchmarkId", benchmarkId),
        )
        if (metrics.isEmpty()) return

        val batchParams =
            metrics.map { metric ->
                MapSqlParameterSource()
                    .addValue("benchmarkId", benchmarkId)
                    .addValue("metricType", metric.name)
            }.toTypedArray()
        jdbcTemplate.batchUpdate(
            "INSERT INTO benchmark_metrics (benchmark_id, metric_type) VALUES (:benchmarkId, :metricType)",
            batchParams,
        )
    }

    private data class BenchmarkRow(
        val benchmarkId: UUID,
        val promptKey: String,
        val datasetId: UUID,
        val nRepetitions: Int,
        val status: String,
    )
}

private fun NamedParameterJdbcTemplate.resolveBenchmarkPromptId(promptKey: PromptKey): UUID =
    queryForObject(
        "SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey",
        MapSqlParameterSource("promptKey", promptKey.value),
        UUID::class.java,
    ) ?: error("Prompt not found for '${promptKey.value}'")

/**
 * [JdbcBenchmarkRepository.replaceTargets]は`upsertBenchmark`の後に呼ばれるため、
 * `benchmarks`行から`prompt_key`を引き直せる（`findPromptKeyForExperiment`と同じ理由）。
 */
private fun NamedParameterJdbcTemplate.findPromptKeyForBenchmark(benchmarkId: UUID): PromptKey =
    queryForObject(
        """
        SELECT p.prompt_key FROM benchmarks b
        JOIN prompts p ON p.prompt_id = b.prompt_id
        WHERE b.benchmark_id = :benchmarkId
        """.trimIndent(),
        MapSqlParameterSource("benchmarkId", benchmarkId),
        String::class.java,
    )?.let { PromptKey(it) } ?: error("Benchmark not found: '$benchmarkId'")

private fun BenchmarkStatus.toDbValue(): String =
    when (this) {
        BenchmarkStatus.Pending -> "Pending"
        BenchmarkStatus.Running -> "Running"
        BenchmarkStatus.Cancelling -> "Cancelling"
        BenchmarkStatus.Completed -> "Completed"
        BenchmarkStatus.Cancelled -> "Cancelled"
        BenchmarkStatus.Failed -> "Failed"
    }

private fun benchmarkStatusFromDbValue(value: String): BenchmarkStatus =
    when (value) {
        "Pending" -> BenchmarkStatus.Pending
        "Running" -> BenchmarkStatus.Running
        "Cancelling" -> BenchmarkStatus.Cancelling
        "Completed" -> BenchmarkStatus.Completed
        "Cancelled" -> BenchmarkStatus.Cancelled
        "Failed" -> BenchmarkStatus.Failed
        else -> error("Unknown benchmark status: '$value'")
    }
