package promptengine.application.pipeline

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import promptengine.application.command.PassthroughIdempotentCommandExecutor
import promptengine.domain.execution.ExecutionOutcome
import promptengine.domain.execution.RawResponse
import promptengine.domain.execution.Usage
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal

/** [ExecuteUseCase]がexecuteLongRunning経由（2フェーズ）で実行され、結果を要約することを検証する。 */
class ExecuteUseCaseTest {
    private val modelProfile = ModelProfile(TokenCount(1_000), "tokenizer", Cost(BigDecimal.ZERO))
    private val request =
        PipelineRequest(
            promptKey = PromptKey("team/greeting"),
            versionRef = VersionRef.Latest,
            variableResolution = PromptRequest(),
            modelProfile = modelProfile,
            budget = TokenCount(1_000),
        )

    @Test
    fun `PipelineContextのExecutionOutcomeを結果に要約する`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val outcome =
            ExecutionOutcome(
                parsedOutput = ParsedOutput(format = OutputFormat.TEXT, raw = "hello"),
                attempts =
                    listOf(
                        RawResponse(
                            content = SensitiveValue.of("hello"),
                            usage = Usage(TokenCount(5), TokenCount(7)),
                            latency = LatencyMs(120),
                        ),
                    ),
            )
        val context =
            PipelineContext(
                request = request,
                mode = PipelineMode.FULL_EXECUTION,
                traceId = "trace-1",
                executionOutcome = outcome,
            )
        every { orchestrator.run(request, PipelineMode.FULL_EXECUTION, "trace-1") } returns context

        val useCase = ExecuteUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(ExecuteCommand(request, "trace-1"))

        result.outputFormat shouldBe "TEXT"
        result.rawContent shouldBe "hello"
        result.usage?.inputTokens shouldBe 5
        result.usage?.outputTokens shouldBe 7
        result.usage?.cost shouldBe BigDecimal.ZERO
        result.latencyMs shouldBe 120
        result.evaluationId shouldBe null
        result.attemptCount shouldBe 1
    }

    @Test
    fun `executionOutcomeが無ければ各フィールドはnullでattemptCountは0になる`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val context = PipelineContext(request = request, mode = PipelineMode.FULL_EXECUTION, traceId = "trace-1")
        every { orchestrator.run(request, PipelineMode.FULL_EXECUTION, "trace-1") } returns context

        val useCase = ExecuteUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(ExecuteCommand(request, "trace-1"))

        result.outputFormat shouldBe null
        result.rawContent shouldBe ""
        result.usage shouldBe null
        result.latencyMs shouldBe null
        result.attemptCount shouldBe 0
    }
}
