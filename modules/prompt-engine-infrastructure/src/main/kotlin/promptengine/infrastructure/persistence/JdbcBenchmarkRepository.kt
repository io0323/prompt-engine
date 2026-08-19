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
 *
 * `targets`/`metrics`はDELETE→再INSERT（`replaceVariants`と同じ方式）ではなく、
 * `ON CONFLICT DO NOTHING`の冪等INSERTのみで持つ（[insertTargetsIfAbsent]/
 * [insertMetricsIfAbsent]）。`Benchmark`のドメインAPI（`start`/`requestCancellation`/
 * `cancel`/`complete`/`fail`）はいずれも`targets`/`metrics`を変更しないため、DELETEは
 * 元々不要だった。加えてフェーズ(c)で`benchmark_item_results.target_id`が
 * `benchmark_targets`をFK参照するようになったため、状態遷移のたびに`save`を呼ぶワーカーが
 * 無条件DELETEを実行すると、既に作成済みの`benchmark_item_results`行がある場合に
 * 外部キー制約違反で失敗する（フェーズ(c)実装時に統合テストで発覚）。
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
    override fun findByStatus(status: BenchmarkStatus): List<Benchmark> =
        transactionTemplate.execute {
            val ids =
                jdbcTemplate.query(
                    "SELECT benchmark_id FROM benchmarks WHERE status = :status ORDER BY created_at",
                    MapSqlParameterSource("status", status.toDbValue()),
                ) { rs, _ -> rs.getObject("benchmark_id", UUID::class.java) }
            ids.mapNotNull { findRow(it) }.map { restore(it) }
        } ?: emptyList()

    @OptIn(PersistenceApi::class)
    override fun save(benchmark: Benchmark): Benchmark =
        transactionTemplate.execute {
            upsertBenchmark(benchmark)
            insertTargetsIfAbsent(benchmark.benchmarkId, benchmark.targets)
            insertMetricsIfAbsent(benchmark.benchmarkId, benchmark.metrics)
            benchmark
        } ?: error("save transaction returned null")

    private fun findRow(benchmarkId: UUID): BenchmarkRow? =
        jdbcTemplate.query(
            """
            SELECT b.benchmark_id, p.prompt_key, b.dataset_id, b.n_repetitions, b.status, b.temperature
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
                temperature = rs.getObject("temperature") as Double?,
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
                metrics = jdbcTemplate.loadBenchmarkMetrics(row.benchmarkId),
                nRepetitions = row.nRepetitions,
                status = benchmarkStatusFromDbValue(row.status),
                temperature = row.temperature,
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

    private fun upsertBenchmark(benchmark: Benchmark) {
        val promptId = jdbcTemplate.resolveBenchmarkPromptId(benchmark.promptKey)
        jdbcTemplate.update(
            """
            INSERT INTO benchmarks (benchmark_id, prompt_id, dataset_id, n_repetitions, status, temperature)
            VALUES (:benchmarkId, :promptId, :datasetId, :nRepetitions, :status, :temperature)
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
                .addValue("status", benchmark.status.toDbValue())
                .addValue("temperature", benchmark.temperature),
        )
    }

    private fun insertTargetsIfAbsent(
        benchmarkId: UUID,
        targets: List<BenchmarkTarget>,
    ) {
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
            ON CONFLICT (target_id) DO NOTHING
            """.trimIndent(),
            batchParams,
        )
    }

    private fun insertMetricsIfAbsent(
        benchmarkId: UUID,
        metrics: Set<BenchmarkMetricType>,
    ) {
        if (metrics.isEmpty()) return

        val batchParams =
            metrics.map { metric ->
                MapSqlParameterSource()
                    .addValue("benchmarkId", benchmarkId)
                    .addValue("metricType", metric.name)
            }.toTypedArray()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO benchmark_metrics (benchmark_id, metric_type)
            VALUES (:benchmarkId, :metricType)
            ON CONFLICT (benchmark_id, metric_type) DO NOTHING
            """.trimIndent(),
            batchParams,
        )
    }

    private data class BenchmarkRow(
        val benchmarkId: UUID,
        val promptKey: String,
        val datasetId: UUID,
        val nRepetitions: Int,
        val status: String,
        val temperature: Double?,
    )
}

private fun NamedParameterJdbcTemplate.resolveBenchmarkPromptId(promptKey: PromptKey): UUID =
    queryForObject(
        "SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey",
        MapSqlParameterSource("promptKey", promptKey.value),
        UUID::class.java,
    ) ?: error("Prompt not found for '${promptKey.value}'")

/**
 * [JdbcBenchmarkRepository.insertTargetsIfAbsent]は`upsertBenchmark`の後に呼ばれるため、
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

/**
 * detektのTooManyFunctions閾値対策で[JdbcBenchmarkRepository]のメンバー関数から抽出した
 * （`resolveBenchmarkPromptId`/`findPromptKeyForBenchmark`と同じ理由）。
 */
private fun NamedParameterJdbcTemplate.loadBenchmarkMetrics(benchmarkId: UUID): Set<BenchmarkMetricType> =
    query(
        "SELECT metric_type FROM benchmark_metrics WHERE benchmark_id = :benchmarkId",
        MapSqlParameterSource("benchmarkId", benchmarkId),
    ) { rs, _ -> BenchmarkMetricType.valueOf(rs.getString("metric_type")) }.toSet()

/**
 * `internal`: `JdbcBenchmarkRepositoryDbValueTest`（同モジュール）が直接検証するため
 * （`LifecycleState.toDbValue`と同じ規約、`PromptRowCodec.kt`参照）。
 */
internal fun BenchmarkStatus.toDbValue(): String =
    when (this) {
        BenchmarkStatus.Pending -> "Pending"
        BenchmarkStatus.Running -> "Running"
        BenchmarkStatus.Cancelling -> "Cancelling"
        BenchmarkStatus.Completed -> "Completed"
        BenchmarkStatus.Cancelled -> "Cancelled"
        BenchmarkStatus.Failed -> "Failed"
    }

internal fun benchmarkStatusFromDbValue(value: String): BenchmarkStatus =
    when (value) {
        "Pending" -> BenchmarkStatus.Pending
        "Running" -> BenchmarkStatus.Running
        "Cancelling" -> BenchmarkStatus.Cancelling
        "Completed" -> BenchmarkStatus.Completed
        "Cancelled" -> BenchmarkStatus.Cancelled
        "Failed" -> BenchmarkStatus.Failed
        else -> error("Unknown benchmark status: '$value'")
    }
