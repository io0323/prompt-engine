package promptengine.application.query

import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkNotFoundException
import promptengine.domain.benchmark.BenchmarkRepository
import java.math.BigDecimal
import java.util.UUID

data class GetBenchmarkResultsQuery(val benchmarkId: UUID)

/** `GET /benchmarks/{id}/results`の1項目分（設計書§13.1、ADR-0035）。 */
data class BenchmarkItemResultSummary(
    val targetId: UUID,
    val itemId: UUID,
    val status: String,
    val accuracyScore: BigDecimal?,
    val consistencyScore: BigDecimal?,
    val determinismScore: BigDecimal?,
    val errorMessage: String?,
)

data class BenchmarkResultsView(
    val benchmarkId: UUID,
    val status: String,
    val items: List<BenchmarkItemResultSummary>,
)

/** `GET /benchmarks/{id}/results`（設計書§13.1、ADR-0035フェーズ(d)「結果取得」）。 */
class GetBenchmarkResultsHandler(
    private val benchmarkRepository: BenchmarkRepository,
    private val benchmarkItemResultRepository: BenchmarkItemResultRepository,
) {
    fun handle(query: GetBenchmarkResultsQuery): BenchmarkResultsView {
        val benchmark =
            benchmarkRepository.findById(query.benchmarkId) ?: throw BenchmarkNotFoundException(query.benchmarkId)
        val records = benchmarkItemResultRepository.findByBenchmarkId(benchmark.benchmarkId)

        return BenchmarkResultsView(
            benchmarkId = benchmark.benchmarkId,
            status = benchmark.status::class.simpleName ?: "Unknown",
            items =
                records.map {
                    BenchmarkItemResultSummary(
                        targetId = it.targetId,
                        itemId = it.itemId,
                        status = it.status,
                        accuracyScore = it.accuracyScore,
                        consistencyScore = it.consistencyScore,
                        determinismScore = it.determinismScore,
                        errorMessage = it.errorMessage,
                    )
                },
        )
    }
}
