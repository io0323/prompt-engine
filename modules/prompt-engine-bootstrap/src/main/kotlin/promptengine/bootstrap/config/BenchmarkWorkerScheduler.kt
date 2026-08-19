package promptengine.bootstrap.config

import org.springframework.scheduling.annotation.Scheduled
import promptengine.infrastructure.benchmark.BenchmarkWorker

/**
 * `benchmark_item_results`を独立したポーリングジョブで処理する（ADR-0035決定3、
 * [OutboxRelayScheduler]と同じ形）。`@Component`による自己登録は行わず、
 * [BenchmarkWorkerConfig]が`@Profile("production")`配下で構築する。
 */
class BenchmarkWorkerScheduler(private val benchmarkWorker: BenchmarkWorker) {
    @Scheduled(fixedDelayString = "\${promptengine.benchmark.worker.poll-interval-ms:2000}")
    fun runOnce() {
        benchmarkWorker.runOnce()
    }
}
