package promptengine.infrastructure.persistence

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.benchmark.BenchmarkItemKey
import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkItemScores
import promptengine.domain.benchmark.ClaimedBenchmarkItem
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * [BenchmarkItemResultRepository]のJDBC実装（`benchmark_item_results`、設計書§12、ADR-0035決定3）。
 *
 * [claimBatch]/[markCompleted]/[markFailed]は`event_bus_outbox`（[EventBusOutboxSource]）と
 * 同一の3段階Claimパターンを踏襲する: (1) `FOR UPDATE SKIP LOCKED`で短いトランザクション内
 * Claim、(2) 実行はトランザクション外（このクラスの外、`BenchmarkWorker`の責務）、
 * (3) `WHERE result_id = :resultId AND claimed_by = :instanceId`でフェンシングした確定。
 * フェンシング喪失（0行更新）時は例外を投げず`benchmark_fencing_lost`をWARNログ出力する
 * （ADR-0025決定3と同じ命名規則。ADR-0025は当初この`claimed_by`条件を欠落させ
 * CodeRabbitレビューで発覚した、同じ欠落を繰り返さない）。
 */
class JdbcBenchmarkItemResultRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : BenchmarkItemResultRepository {
    override fun materialize(pairs: List<BenchmarkItemKey>) {
        if (pairs.isEmpty()) return
        transactionTemplate.execute {
            val batchParams =
                pairs.map { pair ->
                    MapSqlParameterSource()
                        .addValue("resultId", UUID.randomUUID())
                        .addValue("targetId", pair.targetId)
                        .addValue("itemId", pair.itemId)
                }.toTypedArray()
            jdbcTemplate.batchUpdate(
                """
                INSERT INTO benchmark_item_results (result_id, target_id, item_id, status)
                VALUES (:resultId, :targetId, :itemId, '$STATUS_PENDING')
                ON CONFLICT (target_id, item_id) DO NOTHING
                """.trimIndent(),
                batchParams,
            )
        }
    }

    override fun claimBatch(
        instanceId: String,
        claimTimeout: Duration,
        batchSize: Int,
    ): List<ClaimedBenchmarkItem> =
        transactionTemplate.execute {
            val now = Instant.now()
            val claimStaleBefore = now.minus(claimTimeout)
            jdbcTemplate.query(
                """
                UPDATE benchmark_item_results r
                SET claimed_at = :now, claimed_by = :instanceId, status = '$STATUS_CLAIMED'
                FROM (
                    SELECT r2.result_id, t.benchmark_id
                    FROM benchmark_item_results r2
                    JOIN benchmark_targets t ON t.target_id = r2.target_id
                    JOIN benchmarks b ON b.benchmark_id = t.benchmark_id
                    WHERE b.status = 'Running'
                      AND (r2.status = '$STATUS_PENDING'
                           OR (r2.status = '$STATUS_CLAIMED' AND r2.claimed_at < :claimStaleBefore))
                    ORDER BY r2.result_id
                    LIMIT :batchSize
                    FOR UPDATE OF r2 SKIP LOCKED
                ) sub
                WHERE r.result_id = sub.result_id
                RETURNING r.result_id, r.target_id, r.item_id, sub.benchmark_id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("now", Timestamp.from(now))
                    .addValue("instanceId", instanceId)
                    .addValue("claimStaleBefore", Timestamp.from(claimStaleBefore))
                    .addValue("batchSize", batchSize),
            ) { rs, _ ->
                ClaimedBenchmarkItem(
                    resultId = rs.getObject("result_id", UUID::class.java),
                    benchmarkId = rs.getObject("benchmark_id", UUID::class.java),
                    targetId = rs.getObject("target_id", UUID::class.java),
                    itemId = rs.getObject("item_id", UUID::class.java),
                )
            }
        } ?: emptyList()

    override fun markCompleted(
        resultId: UUID,
        instanceId: String,
        scores: BenchmarkItemScores,
    ): Boolean {
        val owned =
            transactionTemplate.execute {
                jdbcTemplate.update(
                    """
                    UPDATE benchmark_item_results
                    SET status = '$STATUS_COMPLETED', accuracy_score = :accuracyScore,
                        consistency_score = :consistencyScore, determinism_score = :determinismScore,
                        completed_at = :now
                    WHERE result_id = :resultId AND claimed_by = :instanceId
                    """.trimIndent(),
                    MapSqlParameterSource()
                        .addValue("accuracyScore", scores.accuracyScore)
                        .addValue("consistencyScore", scores.consistencyScore)
                        .addValue("determinismScore", scores.determinismScore)
                        .addValue("now", Timestamp.from(Instant.now()))
                        .addValue("resultId", resultId)
                        .addValue("instanceId", instanceId),
                )
            } == 1
        if (!owned) logFencingLost(resultId, instanceId, "markCompleted")
        return owned
    }

    override fun markFailed(
        resultId: UUID,
        instanceId: String,
        errorMessage: String,
    ): Boolean {
        val owned =
            transactionTemplate.execute {
                jdbcTemplate.update(
                    """
                    UPDATE benchmark_item_results
                    SET status = '$STATUS_FAILED', error_message = :errorMessage,
                        claimed_at = NULL, claimed_by = NULL
                    WHERE result_id = :resultId AND claimed_by = :instanceId
                    """.trimIndent(),
                    MapSqlParameterSource()
                        .addValue("errorMessage", errorMessage)
                        .addValue("resultId", resultId)
                        .addValue("instanceId", instanceId),
                )
            } == 1
        if (!owned) logFencingLost(resultId, instanceId, "markFailed")
        return owned
    }

    override fun hasIncomplete(benchmarkId: UUID): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM benchmark_item_results r
                JOIN benchmark_targets t ON t.target_id = r.target_id
                WHERE t.benchmark_id = :benchmarkId AND r.status IN ('$STATUS_PENDING', '$STATUS_CLAIMED')
            )
            """.trimIndent(),
            MapSqlParameterSource("benchmarkId", benchmarkId),
            Boolean::class.java,
        ) ?: false

    /**
     * claim_timeoutを超えて別インスタンスに再Claimされた行は、元の所有者が確定してはならない
     * （ADR-0025決定3と同じフェンシング前提。ADR-0035決定3のKDoc参照）。
     */
    private fun logFencingLost(
        resultId: UUID,
        instanceId: String,
        phase: String,
    ) {
        logger.warn(
            "benchmark_fencing_lost resultId={} phase={} instanceId={}",
            resultId,
            phase,
            instanceId,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcBenchmarkItemResultRepository::class.java)
        private const val STATUS_PENDING = "Pending"
        private const val STATUS_CLAIMED = "Claimed"
        private const val STATUS_COMPLETED = "Completed"
        private const val STATUS_FAILED = "Failed"
    }
}
