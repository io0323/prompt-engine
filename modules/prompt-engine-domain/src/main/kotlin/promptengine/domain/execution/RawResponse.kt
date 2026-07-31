package promptengine.domain.execution

import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SensitiveValue

/**
 * [ExecutionAdapter.execute]の成功結果（設計書§2.6ステージ9・§5.8シーケンス、ADR-0014決定2）。
 *
 * [content]は[SensitiveValue]として保持する。実行・修復ラウンドの構築には`content.expose()`で
 * 生値を取り出して使う（実行に必要なため）が、[SensitiveValue.toString]は常に`"***"`を返すため、
 * `RawResponse`をそのまま文字列化・ログ出力・例外メッセージへ埋め込んでも生値は含まれない
 * （CodeRabbitレビュー指摘: `content`が生の`String`のままだと、`RawResponse`自体を意図せず
 * 文字列化する経路が今後追加された場合に構造的な歯止めが無い）。Audit向けの記録データ
 * （[ExecutionOutcome.attempts]）で解析失敗により破棄される応答を`content`ごとマスクする方針
 * （ADR-0014決定9、「プロバイダには送るが記録には残さない」）は、生成側（`ExecutionCoordinator`）が
 * 実値そのものを破棄したマスク済み[SensitiveValue]へ差し替えることで維持する（`SensitiveValue`の
 * `toString()`マスクだけでは`expose()`経由での復元を防げないため）。
 *
 * [retryCount]は、この[RawResponse]を得るまでにリトライを行う[ExecutionAdapter]実装
 * （`RetryingExecutionAdapter`、`prompt-engine-core`）が消費したリトライ回数（0-based。初回成功なら0）。
 */
data class RawResponse(
    val content: SensitiveValue,
    val usage: Usage,
    val latency: LatencyMs,
    val retryCount: Int = 0,
) {
    init {
        require(retryCount >= 0) { "retryCount must not be negative: $retryCount" }
    }
}
