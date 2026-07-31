package promptengine.engine.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.execution.Usage
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount

/** 呼出順に固定のステップを返す/投げるテスト専用[ExecutionAdapter]。 */
private class ScriptedExecutionAdapter(private val steps: List<() -> RawResponse>) : ExecutionAdapter {
    var callCount: Int = 0
        private set

    override fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse {
        val step = steps[callCount]
        callCount++
        return step()
    }
}

class RetryingExecutionAdapterTest {
    private val usage = Usage(TokenCount(1), TokenCount(1))
    private val rendered =
        RenderedPrompt(
            listOf(RenderedMessage(MessageRole.USER, "hi")),
            OutputFormat.TEXT,
            TokenCount(2),
            "hash",
        )

    private fun successStep(): () -> RawResponse = { RawResponse("ok", usage, LatencyMs(1)) }

    private fun failureStep(errorType: ExecutionErrorType): () -> RawResponse =
        { throw ExecutionFailedException(errorType, retryCount = 0) }

    @Test
    fun `CONNECT_TIMEOUTは指定回数までリトライして成功する`() {
        val delegate =
            ScriptedExecutionAdapter(
                listOf(
                    failureStep(ExecutionErrorType.CONNECT_TIMEOUT),
                    failureStep(ExecutionErrorType.CONNECT_TIMEOUT),
                    successStep(),
                ),
            )
        val delays = mutableListOf<Long>()
        val adapter = RetryingExecutionAdapter(delegate, sleeper = { delays += it })
        val policy = ExecutionPolicy(timeoutMs = 1000, maxRetries = 2)

        val response = adapter.execute(rendered, policy)

        response.content shouldBe "ok"
        response.retryCount shouldBe 2
        delegate.callCount shouldBe 3
        delays.size shouldBe 2
    }

    @Test
    fun `READ_TIMEOUTはリトライせず即座に失敗する`() {
        val delegate = ScriptedExecutionAdapter(listOf(failureStep(ExecutionErrorType.READ_TIMEOUT), successStep()))
        val delays = mutableListOf<Long>()
        val adapter = RetryingExecutionAdapter(delegate, sleeper = { delays += it })
        val policy = ExecutionPolicy(timeoutMs = 1000, maxRetries = 2)

        val exception = shouldThrow<ExecutionFailedException> { adapter.execute(rendered, policy) }

        exception.errorType shouldBe ExecutionErrorType.READ_TIMEOUT
        exception.retryCount shouldBe 0
        delegate.callCount shouldBe 1
        delays.size shouldBe 0
    }

    @Test
    fun `RATE_LIMITEDとSERVER_ERRORはリトライ可能`() {
        val delegate =
            ScriptedExecutionAdapter(
                listOf(
                    failureStep(ExecutionErrorType.RATE_LIMITED),
                    failureStep(ExecutionErrorType.SERVER_ERROR),
                    successStep(),
                ),
            )
        val adapter = RetryingExecutionAdapter(delegate, sleeper = {})
        val policy = ExecutionPolicy(timeoutMs = 1000, maxRetries = 2)

        val response = adapter.execute(rendered, policy)

        response.retryCount shouldBe 2
        delegate.callCount shouldBe 3
    }

    @Test
    fun `CLIENT_ERRORはリトライせず即座に失敗する`() {
        val delegate = ScriptedExecutionAdapter(listOf(failureStep(ExecutionErrorType.CLIENT_ERROR)))
        val adapter = RetryingExecutionAdapter(delegate, sleeper = {})
        val policy = ExecutionPolicy(timeoutMs = 1000, maxRetries = 2)

        val exception = shouldThrow<ExecutionFailedException> { adapter.execute(rendered, policy) }

        exception.errorType shouldBe ExecutionErrorType.CLIENT_ERROR
        exception.retryCount shouldBe 0
        delegate.callCount shouldBe 1
    }

    @Test
    fun `maxRetriesを使い切ると最終失敗しretryCountはmaxRetriesと一致する`() {
        val delegate =
            ScriptedExecutionAdapter(
                listOf(
                    failureStep(ExecutionErrorType.SERVER_ERROR),
                    failureStep(ExecutionErrorType.SERVER_ERROR),
                    failureStep(ExecutionErrorType.SERVER_ERROR),
                ),
            )
        val adapter = RetryingExecutionAdapter(delegate, sleeper = {})
        val policy = ExecutionPolicy(timeoutMs = 1000, maxRetries = 2)

        val exception = shouldThrow<ExecutionFailedException> { adapter.execute(rendered, policy) }

        exception.errorType shouldBe ExecutionErrorType.SERVER_ERROR
        exception.retryCount shouldBe 2
        delegate.callCount shouldBe 3
    }

    @Test
    fun `バックオフの待機時間はpolicy backoffのdelayForに従う`() {
        val delegate =
            ScriptedExecutionAdapter(
                listOf(
                    failureStep(ExecutionErrorType.SERVER_ERROR),
                    failureStep(ExecutionErrorType.SERVER_ERROR),
                    successStep(),
                ),
            )
        val delays = mutableListOf<Long>()
        val adapter = RetryingExecutionAdapter(delegate, sleeper = { delays += it })
        val policy = ExecutionPolicy(timeoutMs = 1000, maxRetries = 2)

        adapter.execute(rendered, policy)

        delays shouldBe listOf(policy.backoff.delayFor(1), policy.backoff.delayFor(2))
    }

    @Test
    fun `maxRetries=0ならリトライ可能なエラーでも即座に失敗する`() {
        val delegate = ScriptedExecutionAdapter(listOf(failureStep(ExecutionErrorType.SERVER_ERROR)))
        val adapter = RetryingExecutionAdapter(delegate, sleeper = {})
        val policy = ExecutionPolicy(timeoutMs = 1000, maxRetries = 0)

        val exception = shouldThrow<ExecutionFailedException> { adapter.execute(rendered, policy) }

        exception.retryCount shouldBe 0
        delegate.callCount shouldBe 1
    }
}
