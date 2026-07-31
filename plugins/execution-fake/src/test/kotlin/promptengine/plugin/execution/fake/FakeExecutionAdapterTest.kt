package promptengine.plugin.execution.fake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.Usage
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount

class FakeExecutionAdapterTest {
    private val usage = Usage(TokenCount(10), TokenCount(20))
    private val policy = ExecutionPolicy(timeoutMs = 1000)
    private val prompt =
        RenderedPrompt(listOf(RenderedMessage(MessageRole.USER, "hi")), OutputFormat.TEXT, TokenCount(2), "hash")

    @Test
    fun `Successシナリオは設定したcontent usage latencyをそのまま返す`() {
        val adapter =
            FakeExecutionAdapter(FakeExecutionScenario.Success("hello", usage, LatencyMs(100)))

        val response = adapter.execute(prompt, policy)

        response.content shouldBe "hello"
        response.usage shouldBe usage
        response.latency shouldBe LatencyMs(100)
        response.retryCount shouldBe 0
    }

    @Test
    fun `Delayedシナリオは大きなlatencyを模擬するが実際には待機しない`() {
        val adapter =
            FakeExecutionAdapter(FakeExecutionScenario.Delayed("slow", usage, LatencyMs(5000)))

        val start = System.nanoTime()
        val response = adapter.execute(prompt, policy)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        response.latency shouldBe LatencyMs(5000)
        (elapsedMs < 1000) shouldBe true
    }

    @Test
    fun `ErrorシナリオはExecutionFailedExceptionを投げる`() {
        val adapter = FakeExecutionAdapter(FakeExecutionScenario.Error(ExecutionErrorType.SERVER_ERROR))

        val exception = shouldThrow<ExecutionFailedException> { adapter.execute(prompt, policy) }

        exception.errorType shouldBe ExecutionErrorType.SERVER_ERROR
    }

    @Test
    fun `InvalidStructuredOutputシナリオは不正な生応答をそのまま返す`() {
        val adapter =
            FakeExecutionAdapter(FakeExecutionScenario.InvalidStructuredOutput("not-json", usage, LatencyMs(50)))

        val response = adapter.execute(prompt, policy)

        response.content shouldBe "not-json"
    }

    @Test
    fun `同一シナリオへの複数回の呼出は常に同一の結果を返す`() {
        val adapter = FakeExecutionAdapter(FakeExecutionScenario.Success("hello", usage, LatencyMs(100)))

        val first = adapter.execute(prompt, policy)
        val second = adapter.execute(prompt, policy)

        first shouldBe second
    }
}
