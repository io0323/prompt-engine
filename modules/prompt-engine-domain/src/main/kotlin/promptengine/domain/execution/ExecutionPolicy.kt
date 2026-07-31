package promptengine.domain.execution

/**
 * [ExecutionAdapter.execute]の実行ポリシー（設計書§2.6ステージ9・§3.4疑似コード、ADR-0014決定2）。
 */
data class ExecutionPolicy(
    val timeoutMs: Long,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val backoff: BackoffPolicy = BackoffPolicy(),
    val parseRepair: ParseRepairPolicy = ParseRepairPolicy(),
) {
    init {
        require(timeoutMs > 0) { "timeoutMs must be positive: $timeoutMs" }
        require(maxRetries >= 0) { "maxRetries must not be negative: $maxRetries" }
    }

    companion object {
        private const val DEFAULT_MAX_RETRIES = 2
    }
}
