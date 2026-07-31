package promptengine.domain.execution

import promptengine.domain.shared.LatencyMs

/**
 * [ExecutionAdapter.execute]の成功結果（設計書§2.6ステージ9・§5.8シーケンス、ADR-0014決定2）。
 *
 * [content]は実行・修復ラウンドの構築に必要な生値をそのまま保持する（[SensitiveValue][promptengine.domain.shared.SensitiveValue]と
 * 同じ設計思想）。ログ・Audit・例外メッセージへの出力経路では、この値をそのまま文字列化しない
 * こと（ADR-0014決定9）。
 *
 * [retryCount]は、この[RawResponse]を得るまでにリトライを行う[ExecutionAdapter]実装
 * （`RetryingExecutionAdapter`、`prompt-engine-core`）が消費したリトライ回数（0-based。初回成功なら0）。
 */
data class RawResponse(
    val content: String,
    val usage: Usage,
    val latency: LatencyMs,
    val retryCount: Int = 0,
) {
    init {
        require(retryCount >= 0) { "retryCount must not be negative: $retryCount" }
    }
}
