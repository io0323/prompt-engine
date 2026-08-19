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

        response.content.expose() shouldBe "hello"
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

        response.content.expose() shouldBe "not-json"
    }

    @Test
    fun `同一シナリオへの複数回の呼出は常に同一の結果を返す`() {
        val adapter = FakeExecutionAdapter(FakeExecutionScenario.Success("hello", usage, LatencyMs(100)))

        val first = adapter.execute(prompt, policy)
        val second = adapter.execute(prompt, policy)

        first shouldBe second
    }

    // ---- Cyclingシナリオ（ADR-0035決定5、Consistency/Determinismが「出力が一致する場合」と
    // 「ばらつく場合」を区別できることをテストするために必要） ----

    @Test
    fun `Cyclingシナリオは呼出順にresponsesを巡回して返す`() {
        val adapter = FakeExecutionAdapter(FakeExecutionScenario.Cycling(listOf("a", "b", "c"), usage, LatencyMs(10)))

        adapter.execute(prompt, policy).content.expose() shouldBe "a"
        adapter.execute(prompt, policy).content.expose() shouldBe "b"
        adapter.execute(prompt, policy).content.expose() shouldBe "c"
    }

    @Test
    fun `Cyclingシナリオはresponsesの末尾まで進むと先頭へ戻る`() {
        val adapter = FakeExecutionAdapter(FakeExecutionScenario.Cycling(listOf("a", "b"), usage, LatencyMs(10)))

        adapter.execute(prompt, policy).content.expose() shouldBe "a"
        adapter.execute(prompt, policy).content.expose() shouldBe "b"
        adapter.execute(prompt, policy).content.expose() shouldBe "a"
    }

    @Test
    fun `Cyclingシナリオは全responsesが同一なら常に一致する出力を返す`() {
        val adapter = FakeExecutionAdapter(FakeExecutionScenario.Cycling(listOf("same"), usage, LatencyMs(10)))

        adapter.execute(prompt, policy).content.expose() shouldBe "same"
        adapter.execute(prompt, policy).content.expose() shouldBe "same"
    }

    @Test
    fun `CyclingシナリオのinvocationCountは呼出回数を返す`() {
        val scenario = FakeExecutionScenario.Cycling(listOf("a", "b"), usage, LatencyMs(10))
        val adapter = FakeExecutionAdapter(scenario)

        adapter.execute(prompt, policy)
        adapter.execute(prompt, policy)
        adapter.execute(prompt, policy)

        scenario.invocationCount() shouldBe 3
    }

    @Test
    fun `Cyclingシナリオはresponsesが空だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { FakeExecutionScenario.Cycling(emptyList(), usage, LatencyMs(10)) }
    }
}
