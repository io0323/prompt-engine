package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `IdempotentCommandExecutor.executeLongRunning`のクレームタイムアウト設定
 * （`promptengine.idempotency.*`、ADR-0027・Issue #50）。ハードコードせず
 * `application.yml`/環境変数で上書き可能にする。
 */
@ConfigurationProperties(prefix = "promptengine.idempotency")
data class IdempotencyClaimProperties(
    /**
     * `IN_PROGRESS`予約をクラッシュ後の期限切れとみなすまでの秒数。既定120秒。
     * `executeLongRunning`のoperationは数秒〜数十秒かかり得るため、正常に実行中の予約を
     * 誤って奪取しない程度に長く、かつクラッシュからの現実的な復旧時間になる程度に短くする。
     */
    val claimTimeoutSeconds: Long = DEFAULT_CLAIM_TIMEOUT_SECONDS,
) {
    init {
        require(claimTimeoutSeconds > 0) {
            "promptengine.idempotency.claim-timeout-seconds must be positive: $claimTimeoutSeconds"
        }
    }

    companion object {
        private const val DEFAULT_CLAIM_TIMEOUT_SECONDS = 120L
    }
}
