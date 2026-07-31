package promptengine.engine.execution

import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.render.RenderedPrompt

/**
 * 任意の[ExecutionAdapter]を[ExecutionPolicy.maxRetries]・[ExecutionPolicy.backoff]に基づく
 * リトライでラップするDecorator（設計書§5.8シーケンス`AA -> AA: policy.retry適用(指数バックオフ)`、
 * ADR-0014決定7）。
 *
 * リトライ対象は[RETRYABLE_ERROR_TYPES]に限定する。特にタイムアウトは
 * [ExecutionErrorType.CONNECT_TIMEOUT]（接続確立前、未送信と断定できる＝リトライ可）と
 * [ExecutionErrorType.READ_TIMEOUT]（応答待機中、先方で実行済み・課金済みの可能性を否定できない
 * ＝リトライ不可）を区別する。
 *
 * [sleeper]はバックオフ待機の実装を注入可能にする（既定は[Thread.sleep]）。テストでは
 * 即時実行の実装に差し替えて決定的・高速に検証すること（ADR-0013が`engine.render`/
 * `engine.optimization`で時刻・乱数APIの直接使用を禁じているのと同じ精神、ADR-0014決定7）。
 */
class RetryingExecutionAdapter(
    private val delegate: ExecutionAdapter,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : ExecutionAdapter {
    override fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse {
        var lastFailure: ExecutionFailedException? = null

        for (attempt in 0..policy.maxRetries) {
            try {
                val response = delegate.execute(prompt, policy)
                return RawResponse(response.content, response.usage, response.latency, retryCount = attempt)
            } catch (failure: ExecutionFailedException) {
                val isLastAttempt = attempt == policy.maxRetries
                if (failure.errorType !in RETRYABLE_ERROR_TYPES || isLastAttempt) {
                    throw ExecutionFailedException(failure.errorType, retryCount = attempt, cause = failure)
                }
                lastFailure = failure
                sleeper(policy.backoff.delayFor(attempt + 1))
            }
        }

        // policy.maxRetries >= 0 のためループは必ず return か throw で終了する。
        throw lastFailure ?: error("unreachable: retry loop exited without a result")
    }

    companion object {
        private val RETRYABLE_ERROR_TYPES =
            setOf(
                ExecutionErrorType.CONNECT_TIMEOUT,
                ExecutionErrorType.CONNECTION_FAILURE,
                ExecutionErrorType.RATE_LIMITED,
                ExecutionErrorType.SERVER_ERROR,
            )
    }
}
