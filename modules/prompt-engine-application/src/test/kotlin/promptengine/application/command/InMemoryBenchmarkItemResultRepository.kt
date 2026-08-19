package promptengine.application.command

import promptengine.domain.benchmark.BenchmarkItemKey
import promptengine.domain.benchmark.BenchmarkItemResultRecord
import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkItemScores
import promptengine.domain.benchmark.ClaimedBenchmarkItem
import java.time.Duration
import java.util.UUID

/**
 * テスト用フェイク。単純なMapベースの永続化（ADR-0035）。Claim/フェンシングの実DB挙動
 * （`FOR UPDATE SKIP LOCKED`等）自体は`JdbcBenchmarkItemResultRepositoryIntegrationTest`が
 * 検証するため、ここでは`GetBenchmarkHandler`/`GetBenchmarkResultsHandler`のテストが必要とする
 * [seed]/[findByBenchmarkId]の一貫性のみを保証する。
 */
class InMemoryBenchmarkItemResultRepository : BenchmarkItemResultRepository {
    private data class Row(
        val resultId: UUID,
        val benchmarkId: UUID,
        val targetId: UUID,
        val itemId: UUID,
        var status: String,
        var claimedBy: String? = null,
        var accuracyScore: java.math.BigDecimal? = null,
        var consistencyScore: java.math.BigDecimal? = null,
        var determinismScore: java.math.BigDecimal? = null,
        var errorMessage: String? = null,
    )

    private val rows = mutableListOf<Row>()

    /** [findByBenchmarkId]が返す1行を直接投入する（Query系ハンドラのテスト専用）。 */
    @Suppress("LongParameterList")
    fun seed(
        benchmarkId: UUID,
        targetId: UUID,
        itemId: UUID,
        status: String,
        accuracyScore: java.math.BigDecimal? = null,
        consistencyScore: java.math.BigDecimal? = null,
        determinismScore: java.math.BigDecimal? = null,
        errorMessage: String? = null,
    ) {
        rows +=
            Row(
                resultId = UUID.randomUUID(),
                benchmarkId = benchmarkId,
                targetId = targetId,
                itemId = itemId,
                status = status,
                accuracyScore = accuracyScore,
                consistencyScore = consistencyScore,
                determinismScore = determinismScore,
                errorMessage = errorMessage,
            )
    }

    override fun materialize(pairs: List<BenchmarkItemKey>) = Unit

    override fun claimBatch(
        instanceId: String,
        claimTimeout: Duration,
        batchSize: Int,
    ): List<ClaimedBenchmarkItem> = emptyList()

    override fun markCompleted(
        resultId: UUID,
        instanceId: String,
        scores: BenchmarkItemScores,
    ): Boolean {
        val row = rows.find { it.resultId == resultId } ?: return false
        row.status = "Completed"
        row.accuracyScore = scores.accuracyScore
        row.consistencyScore = scores.consistencyScore
        row.determinismScore = scores.determinismScore
        return true
    }

    override fun markFailed(
        resultId: UUID,
        instanceId: String,
        errorMessage: String,
    ): Boolean {
        val row = rows.find { it.resultId == resultId } ?: return false
        row.status = "Failed"
        row.errorMessage = errorMessage
        return true
    }

    override fun hasIncomplete(benchmarkId: UUID): Boolean =
        rows.any { it.benchmarkId == benchmarkId && it.status in setOf("Pending", "Claimed") }

    override fun findByBenchmarkId(benchmarkId: UUID): List<BenchmarkItemResultRecord> =
        rows.filter { it.benchmarkId == benchmarkId }.map {
            BenchmarkItemResultRecord(
                resultId = it.resultId,
                targetId = it.targetId,
                itemId = it.itemId,
                status = it.status,
                accuracyScore = it.accuracyScore,
                consistencyScore = it.consistencyScore,
                determinismScore = it.determinismScore,
                errorMessage = it.errorMessage,
            )
        }
}
