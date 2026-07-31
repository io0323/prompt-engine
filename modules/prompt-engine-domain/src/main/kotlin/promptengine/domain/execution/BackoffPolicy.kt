package promptengine.domain.execution

/**
 * リトライ間隔の指数バックオフ設定（設計書実装ガイド§6.8「リトライは指数バックオフ」、ADR-0014決定2）。
 */
data class BackoffPolicy(
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val multiplier: Double = DEFAULT_MULTIPLIER,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
) {
    init {
        require(initialDelayMs >= 0) { "initialDelayMs must not be negative: $initialDelayMs" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0: $multiplier" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs: $maxDelayMs < $initialDelayMs" }
    }

    /**
     * [attempt]（1-based、1回目のリトライ＝1）回目のリトライ前に待機すべき時間（ミリ秒）。
     * `initialDelayMs * multiplier^(attempt-1)` を [maxDelayMs] で頭打ちする。
     */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1: $attempt" }
        val raw = initialDelayMs * Math.pow(multiplier, (attempt - 1).toDouble())
        return raw.toLong().coerceAtMost(maxDelayMs)
    }

    companion object {
        private const val DEFAULT_INITIAL_DELAY_MS = 500L
        private const val DEFAULT_MULTIPLIER = 2.0
        private const val DEFAULT_MAX_DELAY_MS = 8000L
    }
}
