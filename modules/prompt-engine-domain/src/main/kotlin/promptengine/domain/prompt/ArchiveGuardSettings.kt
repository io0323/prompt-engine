package promptengine.domain.prompt

import java.time.Duration
import java.time.Instant

/**
 * `archive`ガードを`execution_logs`ベースで判定するための設定（Issue #48、ADR-0026決定5）。
 *
 * `prompt-engine-bootstrap`が`promptengine.archive.*`（`@ConfigurationProperties`）から
 * 組み立てて`ArchiveHandler`へ渡す。ハンドラ自身はSpringの設定バインディングを知らない
 * （`OutboxRelayer`が`OutboxRelayProperties`ではなく素の値を受け取るのと同じ方針、
 * ADR-0025決定5）。
 *
 * [executionLogsCutoverAt]は`execution_logs`の内容が信頼できるようになった時刻
 * （＝`ExecutionLogSubscriber`が本番稼働を始めた時刻）。これ以前に作られたVersionは
 * 「実行記録が無い」ことから参照ゼロを結論できないため、判断不能として扱う。
 *
 * [inactivityThreshold]は「直近この期間に実行が無ければ参照ゼロとみなす」判定窓。
 * 設計書に既定値の記載が無いため90日とした（ADR-0026決定5）。
 */
data class ArchiveGuardSettings(
    val executionLogsCutoverAt: Instant,
    val inactivityThreshold: Duration = DEFAULT_INACTIVITY_THRESHOLD,
) {
    init {
        require(!inactivityThreshold.isNegative && !inactivityThreshold.isZero) {
            "inactivityThreshold must be positive: $inactivityThreshold"
        }
    }

    companion object {
        /** 既定の無活動判定窓（90日）。 */
        val DEFAULT_INACTIVITY_THRESHOLD: Duration = Duration.ofDays(90)
    }
}
