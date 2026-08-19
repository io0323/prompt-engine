package promptengine.domain.benchmark

import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/**
 * `benchmark_item_results`（設計書§12、ADR-0035決定3）の永続化インターフェース。
 * 実装は`prompt-engine-infrastructure`（`JdbcBenchmarkItemResultRepository`）。
 *
 * [Benchmark]/[GoldenDataset]の永続化とは異なり、ここで扱う行は「非同期ワーカーの作業単位」
 * であり、Claim（`FOR UPDATE SKIP LOCKED`）・フェンシング付き確定というOutbox
 * （ADR-0025）/IdempotentCommandExecutor（ADR-0027）と同一パターンのSQL操作が本質であるため、
 * `Benchmark`/`GoldenDataset`のような値の入れ替え（`save`）ではなく専用の操作を並べる。
 */
interface BenchmarkItemResultRepository {
    /**
     * [pairs]（Target×Datasetの組）ごとに`Pending`行を1件用意する。既に存在する組は無視する
     * （`ON CONFLICT DO NOTHING`相当）。複数ワーカーが同じBenchmarkの`Pending`→`Running`遷移を
     * 同時に処理しても安全（冪等）である前提（ADR-0035決定3、Benchmark単位のClaimは
     * 行わないため）。
     */
    fun materialize(pairs: List<BenchmarkItemKey>)

    /**
     * `status='Pending'`、または`status='Claimed'`かつ`claimed_at`が[claimTimeout]を
     * 超えている行を最大[batchSize]件Claimする。親Benchmarkの`status`が`Running`である行のみ
     * 対象とする（`Cancelling`/`Cancelled`のBenchmarkの未Claim項目は新規に拾わない、
     * ADR-0035決定3）。
     */
    fun claimBatch(
        instanceId: String,
        claimTimeout: Duration,
        batchSize: Int,
    ): List<ClaimedBenchmarkItem>

    /**
     * [resultId]を`Completed`として確定する。`WHERE result_id = :resultId AND
     * claimed_by = :instanceId`でフェンシングし、0行更新（他インスタンスが再Claim済み）
     * なら`false`を返す（例外は投げない、ADR-0025と同じ規約）。
     */
    fun markCompleted(
        resultId: UUID,
        instanceId: String,
        scores: BenchmarkItemScores,
    ): Boolean

    /**
     * [resultId]を`Failed`として確定する（`claimed_at`/`claimed_by`はクリアする）。
     * [markCompleted]と同じフェンシング規約に従う。
     */
    fun markFailed(
        resultId: UUID,
        instanceId: String,
        errorMessage: String,
    ): Boolean

    /** [benchmarkId]配下に`Pending`/`Claimed`（未終端）の行が1件でも残っているか。 */
    fun hasIncomplete(benchmarkId: UUID): Boolean
}

/** [BenchmarkItemResultRepository.materialize]の入力（Target×Datasetの組）。 */
data class BenchmarkItemKey(val targetId: UUID, val itemId: UUID)

/** [BenchmarkItemResultRepository.claimBatch]がClaimした1件。 */
data class ClaimedBenchmarkItem(
    val resultId: UUID,
    val benchmarkId: UUID,
    val targetId: UUID,
    val itemId: UUID,
)

/** [BenchmarkItemResultRepository.markCompleted]に渡す採点結果（指標ごとに`null`可）。 */
data class BenchmarkItemScores(
    val accuracyScore: BigDecimal? = null,
    val consistencyScore: BigDecimal? = null,
    val determinismScore: BigDecimal? = null,
)
