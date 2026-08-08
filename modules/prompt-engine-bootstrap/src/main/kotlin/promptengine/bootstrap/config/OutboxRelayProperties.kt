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
    init {
        // 誤設定（0以下）はOutboxRelayerを機能不全にする（batchSize<=0は毎回0件クレーム、
        // claimTimeoutSeconds<=0は自分がクレームした行を次のポーリングで即座に再クレーム
        // 対象にしてしまう）。Bean生成前の設定バインディング時点でfail-fastする
        // （CodeRabbitレビュー指摘）。
        require(batchSize > 0) { "promptengine.eventbus.relay.batch-size must be positive: $batchSize" }
        require(claimTimeoutSeconds > 0) {
            "promptengine.eventbus.relay.claim-timeout-seconds must be positive: $claimTimeoutSeconds"
        }
        require(pollIntervalMs > 0) {
            "promptengine.eventbus.relay.poll-interval-ms must be positive: $pollIntervalMs"
        }
    }

    companion object {
        private const val DEFAULT_POLL_INTERVAL_MS = 750L
        private const val DEFAULT_BATCH_SIZE = 50
        private const val DEFAULT_CLAIM_TIMEOUT_SECONDS = 30L
    }
}
