package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `PromptCache`の設定（`promptengine.cache.*`、ADR-0033決定5・b）。
 *
 * [ttlSeconds]は無効化イベントが届く前に読まれる窓の上限。既定30秒は新規の数値ではなく、
 * 本システムが既に持つ`OutboxRelayProperties.claimTimeoutSeconds`（イベント配信が
 * 最悪ケースでとり得る遅延の上限）の既定値に揃えたもの（ADR-0033参照）。
 */
@ConfigurationProperties(prefix = "promptengine.cache")
data class CacheProperties(
    val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    val redisUri: String = DEFAULT_REDIS_URI,
) {
    init {
        require(ttlSeconds > 0) { "promptengine.cache.ttl-seconds must be positive: $ttlSeconds" }
    }

    fun toTtl(): Duration = Duration.ofSeconds(ttlSeconds)

    companion object {
        const val DEFAULT_TTL_SECONDS = 30L
        const val DEFAULT_REDIS_URI = "redis://localhost:6379"
    }
}
