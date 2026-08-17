package promptengine.domain.benchmark

import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.PersistenceApi
import java.util.UUID

/**
 * Benchmark Bounded ContextのAggregate Root（設計書§2.12・§4.1・§4.3・§12、ADR-0035）。
 *
 * `Experiment`とは無関係の独立したAggregateである（ADR-0035決定1。`Experiment`の
 * 「Variant配分合計=100%」不変条件はオンラインのトラフィック分割を前提にしたもので、
 * オフライン評価であるBenchmarkには適用できない。既存の`Experiment`/`Variant`/
 * `TrafficPolicy`・`experiments`/`variants`テーブルには一切触れない）。
 *
 * 不変条件（設計書§4.3）: Target 1件以上（[create]で検証）。
 *
 * プライマリコンストラクタは`internal`。新規作成は[create]、永続化層からの復元は
 * [restore]を使うこと（`Experiment`/`GoldenDataset`と同じ規約）。
 */
@ConsistentCopyVisibility
data class Benchmark internal constructor(
    val benchmarkId: UUID,
    val promptKey: PromptKey,
    val datasetId: UUID,
    val targets: List<BenchmarkTarget>,
    val metrics: Set<BenchmarkMetricType>,
    val nRepetitions: Int,
    val status: BenchmarkStatus,
) {
    companion object {
        private const val MIN_TARGETS = 1
        private const val MIN_REPETITIONS = 1

        /** Benchmarkを新規作成する（`Pending`状態）。 */
        fun create(
            promptKey: PromptKey,
            datasetId: UUID,
            targets: List<BenchmarkTarget>,
            metrics: Set<BenchmarkMetricType>,
            nRepetitions: Int,
        ): Benchmark {
            requireValidTargets(targets)
            require(metrics.isNotEmpty()) { "Benchmark requires at least 1 metric" }
            require(nRepetitions >= MIN_REPETITIONS) {
                "nRepetitions must be at least $MIN_REPETITIONS: was $nRepetitions"
            }
            return Benchmark(
                benchmarkId = UUID.randomUUID(),
                promptKey = promptKey,
                datasetId = datasetId,
                targets = targets,
                metrics = metrics,
                nRepetitions = nRepetitions,
                status = BenchmarkStatus.Pending,
            )
        }

        private fun requireValidTargets(targets: List<BenchmarkTarget>) {
            require(targets.size >= MIN_TARGETS) {
                "Benchmark requires at least $MIN_TARGETS target: was ${targets.size}"
            }
            val semVers = targets.map { it.promptVersionSemVer }
            require(semVers.size == semVers.toSet().size) { "Target versions must be unique: $semVers" }
        }

        /** 永続化層からの復元専用（`Experiment.restore`と同じパターン、ADR-0034）。 */
        @PersistenceApi
        fun restore(memento: BenchmarkMemento): Benchmark =
            Benchmark(
                memento.benchmarkId,
                memento.promptKey,
                memento.datasetId,
                memento.targets,
                memento.metrics,
                memento.nRepetitions,
                memento.status,
            )
    }

    /**
     * `Pending`→`Running`。フェーズ(c)の非同期ワーカーが最初の項目をClaimする直前に呼ぶ
     * （ADR-0035決定3）。
     */
    fun start(): Benchmark {
        assertStatus(BenchmarkStatus.Pending, "start")
        return copy(status = BenchmarkStatus.Running)
    }

    /**
     * `Running`→`Cancelling`。中断要求（ADR-0035決定3）。ワーカーは次のポーリングで
     * `Cancelling`を検知し、新規Claimを止めて[cancel]へ進める。実行中の項目を
     * 強制中断はしない。
     */
    fun requestCancellation(): Benchmark {
        assertStatus(BenchmarkStatus.Running, "requestCancellation")
        return copy(status = BenchmarkStatus.Cancelling)
    }

    /** `Cancelling`→`Cancelled`。ワーカーが未Claim項目の処理を止めた後に呼ぶ。 */
    fun cancel(): Benchmark {
        assertStatus(BenchmarkStatus.Cancelling, "cancel")
        return copy(status = BenchmarkStatus.Cancelled)
    }

    /** `Running`→`Completed`。全項目の処理が終わった時点でワーカーが呼ぶ。 */
    fun complete(): Benchmark {
        assertStatus(BenchmarkStatus.Running, "complete")
        return copy(status = BenchmarkStatus.Completed)
    }

    /**
     * `Running`/`Cancelling`→`Failed`。個別項目の失敗（ADR-0035決定3、M2では自動リトライ
     * しない）とは別に、Benchmark全体が続行不能になった場合に呼ぶ。
     */
    fun fail(): Benchmark {
        if (status != BenchmarkStatus.Running && status != BenchmarkStatus.Cancelling) {
            throw InvalidStateTransitionException(status::class.simpleName ?: "Unknown", "fail")
        }
        return copy(status = BenchmarkStatus.Failed)
    }

    private fun assertStatus(
        expected: BenchmarkStatus,
        operation: String,
    ) {
        if (status != expected) {
            throw InvalidStateTransitionException(status::class.simpleName ?: "Unknown", operation)
        }
    }
}
