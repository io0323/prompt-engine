package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Kafka互換Brokerの購読側設定（`promptengine.eventbus.subscriber.*`、ADR-0026決定1）。
 *
 * [OutboxRelayProperties]と同じ方針: 実装（`KafkaSubscriberRunner`）自体は
 * `@ConfigurationProperties`を知らず素の値だけを受け取り、バインディングの配線は
 * `prompt-engine-bootstrap`に閉じる（CLAUDE.md「具象クラスのDI結線はbootstrapのみ」）。
 * `init`ブロックで不正値をfail-fastさせるのも同じ（誤設定は購読を機能不全にする:
 * `pollIntervalMs<=0`は`@Scheduled`のfixedDelayとして不正、`pollTimeoutMs<=0`は
 * ポーリングが常に即時空振りする）。
 */
@ConfigurationProperties(prefix = "promptengine.eventbus.subscriber")
data class SubscriberProperties(
    /** 各購読ジョブのポーリング間隔（ミリ秒）。既定500ms。 */
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    /** `KafkaConsumer.poll`の待ち時間（ミリ秒）。既定500ms。 */
    val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS,
    /** 1回のpollで取得する最大レコード数。既定100件。 */
    val maxPollRecords: Int = DEFAULT_MAX_POLL_RECORDS,
) {
    init {
        require(pollIntervalMs > 0) {
            "promptengine.eventbus.subscriber.poll-interval-ms must be positive: $pollIntervalMs"
        }
        require(pollTimeoutMs > 0) {
            "promptengine.eventbus.subscriber.poll-timeout-ms must be positive: $pollTimeoutMs"
        }
        require(maxPollRecords > 0) {
            "promptengine.eventbus.subscriber.max-poll-records must be positive: $maxPollRecords"
        }
    }

    companion object {
        private const val DEFAULT_POLL_INTERVAL_MS = 500L
        private const val DEFAULT_POLL_TIMEOUT_MS = 500L
        private const val DEFAULT_MAX_POLL_RECORDS = 100
    }
}
