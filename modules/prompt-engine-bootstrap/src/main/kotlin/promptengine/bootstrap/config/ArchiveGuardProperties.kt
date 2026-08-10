package promptengine.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties
import promptengine.domain.prompt.ArchiveGuardSettings
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * `archive`ガードの設定（`promptengine.archive.*`、Issue #48、ADR-0026決定5）。
 *
 * [OutboxRelayProperties]と同じ方針で、`init`ブロックで不正値をfail-fastさせる
 * （誤設定のままガードが機能しない状態で起動するのを防ぐ）。
 */
@ConfigurationProperties(prefix = "promptengine.archive")
data class ArchiveGuardProperties(
    /**
     * `execution_logs`の内容が信頼できるようになった時刻（ISO-8601）。
     * `prompt_versions.created_at`がこれ以前のVersionは、実行記録の不在から参照ゼロを
     * 結論できないため判断不能として扱い、従来通り`force=true`を必須にする。
     *
     * 既定値はP10bのシップ日。運用でカットオーバーを引き直す場合はこの値を変更する。
     */
    val executionLogsCutoverAt: String = DEFAULT_CUTOVER_AT,
    /** 「直近この日数に実行が無ければ参照ゼロとみなす」判定窓。既定90日。 */
    val inactivityThresholdDays: Long = DEFAULT_INACTIVITY_THRESHOLD_DAYS,
) {
    init {
        require(inactivityThresholdDays > 0) {
            "promptengine.archive.inactivity-threshold-days must be positive: $inactivityThresholdDays"
        }
        require(runCatching { Instant.parse(executionLogsCutoverAt) }.isSuccess) {
            "promptengine.archive.execution-logs-cutover-at must be an ISO-8601 instant: $executionLogsCutoverAt"
        }
    }

    /** ドメイン側の設定型へ変換する（ハンドラはSpringの設定バインディングを知らない）。 */
    @Throws(DateTimeParseException::class)
    fun toSettings(): ArchiveGuardSettings =
        ArchiveGuardSettings(
            executionLogsCutoverAt = Instant.parse(executionLogsCutoverAt),
            inactivityThreshold = Duration.ofDays(inactivityThresholdDays),
        )

    companion object {
        /** P10b（`execution_logs`への書き込み経路が入ったリリース）のシップ日。 */
        const val DEFAULT_CUTOVER_AT = "2026-08-09T00:00:00Z"
        private const val DEFAULT_INACTIVITY_THRESHOLD_DAYS = 90L
    }
}
