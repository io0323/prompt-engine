package promptengine.domain.execution

import promptengine.domain.render.RenderedPrompt

/**
 * APAP委譲の入口（設計書§3.4疑似コード、§2.6ステージ9・§5.8シーケンス、§16拡張ポイント#11）。
 * PEはProvider非依存で、[RenderedPrompt]と[ExecutionPolicy]のみを受け取る。
 *
 * 実APAP接続（`ApapExecutionAdapter`）はM2で`prompt-engine-infrastructure`に実装する。
 * その際、APAPへ渡す`ExecutionRequest`（プロバイダ固有の`messages`/`modelHints`表現）への
 * 変換は当該実装内部の関心事であり、本インターフェースの引数には現れない（ADR-0014決定1）。
 *
 * リトライ（[ExecutionPolicy.maxRetries]・[BackoffPolicy]に基づく）は、本インターフェースの
 * 実装自体が行うことを想定しない。呼出側（`RetryingExecutionAdapter`、`prompt-engine-core`）が
 * 任意の[ExecutionAdapter]実装をラップしてリトライを付与する（ADR-0014決定7）。
 */
interface ExecutionAdapter {
    /**
     * [prompt]を実行する。[policy]はタイムアウト等の制御に用いる。
     *
     * 失敗時は[ExecutionFailedException]を投げる。冪等でない副作用（プロバイダへの実行要求）を
     * 伴う操作であるため、呼出側でのリトライは[ExecutionErrorType]に基づき安全性が確認された
     * エラー種別に限定すること（ADR-0014決定7）。
     */
    fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse
}
