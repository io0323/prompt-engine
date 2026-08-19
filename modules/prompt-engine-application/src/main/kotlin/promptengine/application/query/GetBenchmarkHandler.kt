package promptengine.application.query

import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkNotFoundException
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.GoldenDatasetNotFoundException
import promptengine.domain.benchmark.GoldenDatasetRepository
import java.util.UUID

data class GetBenchmarkQuery(val benchmarkId: UUID)

/** `GET /benchmarks/{id}`の1Target分（設計書§13.1、ADR-0035）。 */
data class BenchmarkTargetSummary(val targetId: UUID, val semVer: String)

/**
 * `GET /benchmarks/{id}`の進捗集計（ADR-0035フェーズ(d)）。[totalItems]は
 * `benchmark_item_results`の行数（Target×Datasetの組数）であり、[BenchmarkView.estimatedExecutionCount]
 * （実行回数＝行数×N）とは異なる単位である点に注意（materialize前＝`Pending`のままは全て0）。
 */
data class BenchmarkProgressSummary(
    val totalItems: Int,
    val completedItems: Int,
    val failedItems: Int,
    val pendingItems: Int,
)

data class BenchmarkView(
    val benchmarkId: UUID,
    val promptKey: String,
    val datasetId: UUID,
    val targets: List<BenchmarkTargetSummary>,
    val metrics: Set<String>,
    val nRepetitions: Int,
    val temperature: Double?,
    val status: String,
    val estimatedExecutionCount: Int,
    val progress: BenchmarkProgressSummary,
)

/** `GET /benchmarks/{id}`（設計書§13.1、ADR-0035フェーズ(d)「取得（進捗含む）」）。 */
class GetBenchmarkHandler(
    private val benchmarkRepository: BenchmarkRepository,
    private val goldenDatasetRepository: GoldenDatasetRepository,
    private val benchmarkItemResultRepository: BenchmarkItemResultRepository,
) {
    fun handle(query: GetBenchmarkQuery): BenchmarkView {
        val benchmark =
            benchmarkRepository.findById(query.benchmarkId) ?: throw BenchmarkNotFoundException(query.benchmarkId)
        val dataset =
            goldenDatasetRepository.findById(benchmark.datasetId)
                ?: throw GoldenDatasetNotFoundException(benchmark.datasetId)
        val records = benchmarkItemResultRepository.findByBenchmarkId(benchmark.benchmarkId)

        return BenchmarkView(
            benchmarkId = benchmark.benchmarkId,
            promptKey = benchmark.promptKey.value,
            datasetId = benchmark.datasetId,
            targets = benchmark.targets.map { BenchmarkTargetSummary(it.targetId, it.promptVersionSemVer.toString()) },
            metrics = benchmark.metrics.map { it.name }.toSet(),
            nRepetitions = benchmark.nRepetitions,
            temperature = benchmark.temperature,
            status = benchmark.status::class.simpleName ?: "Unknown",
            estimatedExecutionCount = benchmark.estimatedExecutionCount(dataset.items.size),
            progress =
                BenchmarkProgressSummary(
                    totalItems = records.size,
                    completedItems = records.count { it.status == "Completed" },
                    failedItems = records.count { it.status == "Failed" },
                    pendingItems = records.count { it.status == "Pending" || it.status == "Claimed" },
                ),
        )
    }
}
