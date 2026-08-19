package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Benchmark実行ワーカーのポーリング設定（`promptengine.benchmark.worker.*`、ADR-0035決定3）。
 * [OutboxRelayProperties]と同じ形（fail-fastバリデーション）。
 */
@ConfigurationProperties(prefix = "promptengine.benchmark.worker")
data class BenchmarkWorkerProperties(
    /** ポーリング間隔（ミリ秒）。既定2000ms（項目1件の実行が数秒〜数十秒かかるため、Outboxより長め）。 */
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    /** 1回のClaimで取得する最大項目数。既定20件。 */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** Claimしたインスタンスがクラッシュしたとみなすまでの秒数。既定60秒。 */
    val claimTimeoutSeconds: Long = DEFAULT_CLAIM_TIMEOUT_SECONDS,
    /** 1回のプロバイダ実行のタイムアウト（ミリ秒）。既定30000ms。 */
    val executionTimeoutMs: Long = DEFAULT_EXECUTION_TIMEOUT_MS,
) {
    init {
        require(batchSize > 0) { "promptengine.benchmark.worker.batch-size must be positive: $batchSize" }
        require(claimTimeoutSeconds > 0) {
            "promptengine.benchmark.worker.claim-timeout-seconds must be positive: $claimTimeoutSeconds"
        }
        require(pollIntervalMs > 0) {
            "promptengine.benchmark.worker.poll-interval-ms must be positive: $pollIntervalMs"
        }
        require(executionTimeoutMs > 0) {
            "promptengine.benchmark.worker.execution-timeout-ms must be positive: $executionTimeoutMs"
        }
    }

    companion object {
        private const val DEFAULT_POLL_INTERVAL_MS = 2_000L
        private const val DEFAULT_BATCH_SIZE = 20
        private const val DEFAULT_CLAIM_TIMEOUT_SECONDS = 60L
        private const val DEFAULT_EXECUTION_TIMEOUT_MS = 30_000L
    }
}
