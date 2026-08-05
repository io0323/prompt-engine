package promptengine.application.pipeline

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import promptengine.application.command.PassthroughIdempotentCommandExecutor
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal

/** [RenderUseCase]がP8の[PipelineOrchestrator]をRENDER_ONLYモードで呼び、結果を要約することを検証する。 */
class RenderUseCaseTest {
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
    fun `PipelineContextのRenderedPromptを結果に要約する`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val rendered =
            RenderedPrompt(
                messages = listOf(RenderedMessage(role = MessageRole.USER, content = "hi")),
                outputFormat = OutputFormat.TEXT,
                tokenEstimate = TokenCount(10),
                renderHash = "sha256:abc",
            )
        val context =
            PipelineContext(
                request = request,
                mode = PipelineMode.RENDER_ONLY,
                traceId = "trace-1",
                rendered = rendered,
            )
        every { orchestrator.run(request, PipelineMode.RENDER_ONLY, "trace-1") } returns context

        val useCase = RenderUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(RenderCommand(request, "trace-1"))

        result.outputFormat shouldBe "TEXT"
        result.tokenEstimate shouldBe 10
        result.renderHash shouldBe "sha256:abc"
        result.messages shouldBe listOf(RenderedMessageSummary("USER", "hi"))
        result.warnings shouldBe emptyList()
    }

    @Test
    fun `renderedが無ければ各フィールドはnullでmessagesは空になる`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val context = PipelineContext(request = request, mode = PipelineMode.RENDER_ONLY, traceId = "trace-1")
        every { orchestrator.run(request, PipelineMode.RENDER_ONLY, "trace-1") } returns context

        val useCase = RenderUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(RenderCommand(request, "trace-1"))

        result.outputFormat shouldBe null
        result.tokenEstimate shouldBe null
        result.renderHash shouldBe null
        result.messages shouldBe emptyList()
    }
}
