package promptengine.domain.benchmark

/**
 * [Benchmark]の状態（設計書§12 `benchmarks.status`、ADR-0035決定3）。
 *
 * `Pending`→`Running`→(`Completed`|`Failed`)、または`Running`→`Cancelling`→`Cancelled`。
 * 実際の遷移駆動（項目のClaim・実行・進捗判定）はフェーズ(c)の非同期ワーカーの責務であり、
 * 本Aggregateはその遷移が不変条件に従うことのみを保証する。
 */
sealed class BenchmarkStatus {
    data object Pending : BenchmarkStatus()

    data object Running : BenchmarkStatus()

    data object Cancelling : BenchmarkStatus()

    data object Completed : BenchmarkStatus()

    data object Cancelled : BenchmarkStatus()

    data object Failed : BenchmarkStatus()
}
