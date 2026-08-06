package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Outbox → Broker中継のポーリング設定（`promptengine.eventbus.relay.*`、ADR-0025決定5）。
 * ハードコードせず`application.yml`/環境変数で上書き可能にする。
 */
@ConfigurationProperties(prefix = "promptengine.eventbus.relay")
data class OutboxRelayProperties(
    /** ポーリング間隔（ミリ秒）。既定750ms。 */
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    /** 1回のクレームで取得する最大件数。既定50件。 */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** クレームしたプロセスがクラッシュしたとみなすまでの秒数。既定30秒。 */
    val claimTimeoutSeconds: Long = DEFAULT_CLAIM_TIMEOUT_SECONDS,
) {
    companion object {
        private const val DEFAULT_POLL_INTERVAL_MS = 750L
        private const val DEFAULT_BATCH_SIZE = 50
        private const val DEFAULT_CLAIM_TIMEOUT_SECONDS = 30L
    }
}
