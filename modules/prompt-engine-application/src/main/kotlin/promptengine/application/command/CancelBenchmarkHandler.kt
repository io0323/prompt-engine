package promptengine.application.command

import promptengine.domain.benchmark.BenchmarkNotFoundException
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.shared.IdempotentCommandExecutor
import java.util.UUID

/** `POST /benchmarks/{id}/cancel`（設計書§13.1、ADR-0035決定3）。 */
data class CancelBenchmarkCommand(
    val benchmarkId: UUID,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$benchmarkId"
}

data class CancelBenchmarkResult(val benchmarkId: UUID, val status: String)

/**
 * `Running`→`Cancelling`（[promptengine.domain.benchmark.Benchmark.requestCancellation]）。
 * `Cancelling`→`Cancelled`への最終遷移はワーカー（`BenchmarkWorker`、次のポーリングで未Claim
 * 項目を確認してから）が行う（ADR-0035決定3）。`Pending`/`Completed`等からの呼出は
 * `InvalidStateTransitionException`（409）となる（`Benchmark`の状態機械が保証、新規の
 * バリデーションは追加しない）。
 */
class CancelBenchmarkHandler(
    private val benchmarkRepository: BenchmarkRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
) {
    fun handle(command: CancelBenchmarkCommand): CancelBenchmarkResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            CancelBenchmarkResult::class.java,
        ) {
            val benchmark =
                benchmarkRepository.findById(command.benchmarkId)
                    ?: throw BenchmarkNotFoundException(command.benchmarkId)
            val requested = benchmark.requestCancellation()
            val saved = benchmarkRepository.save(requested)
            CancelBenchmarkResult(saved.benchmarkId, saved.status::class.simpleName ?: "Unknown")
        }
}
