package promptengine.application.pipeline

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import promptengine.application.command.PassthroughIdempotentCommandExecutor
import promptengine.domain.event.EventContext
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationReport
import java.math.BigDecimal
import java.time.Instant

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

    private fun promptVersion(output: OutputDeclaration?) =
        Prompt.create(
            PromptKey("team/greeting"),
            NewPromptVersion(SemVer(1, 0, 0), PromptContent("hello"), output = output),
            EventContext(actor = "user:test", traceId = "trace-1", occurredAt = Instant.EPOCH),
        ).first.versions.first()

    @Test
    fun `promptVersionがあればversionとoutputSchemaRefを結果へ反映する`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val context =
            PipelineContext(
                request = request,
                mode = PipelineMode.RENDER_ONLY,
                traceId = "trace-1",
                promptVersion = promptVersion(OutputDeclaration(OutputFormat.JSON, "schemas/x")),
            )
        every { orchestrator.run(request, PipelineMode.RENDER_ONLY, "trace-1") } returns context

        val useCase = RenderUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(RenderCommand(request, "trace-1"))

        result.version shouldBe "1.0.0"
        result.outputSchemaRef shouldBe "schemas/x"
    }

    @Test
    fun `promptVersionのoutputがなければoutputSchemaRefはnullになる`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val context =
            PipelineContext(
                request = request,
                mode = PipelineMode.RENDER_ONLY,
                traceId = "trace-1",
                promptVersion = promptVersion(output = null),
            )
        every { orchestrator.run(request, PipelineMode.RENDER_ONLY, "trace-1") } returns context

        val useCase = RenderUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(RenderCommand(request, "trace-1"))

        result.version shouldBe "1.0.0"
        result.outputSchemaRef shouldBe null
    }

    @Test
    fun `validationReportがあればWARNINGのみwarningsへ変換しERRORは除外する`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val report =
            ValidationReport(
                listOf(
                    Finding(ruleId = "R1", path = "$.x", severity = Severity.ERROR, message = "bad"),
                    Finding(ruleId = "R2", path = "$.y", severity = Severity.WARNING, message = "warn"),
                ),
            )
        val context =
            PipelineContext(
                request = request,
                mode = PipelineMode.RENDER_ONLY,
                traceId = "trace-1",
                validationReport = report,
            )
        every { orchestrator.run(request, PipelineMode.RENDER_ONLY, "trace-1") } returns context

        val useCase = RenderUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(RenderCommand(request, "trace-1"))

        result.warnings shouldBe listOf(FindingSummary("R2", "$.y", "WARNING", "warn"))
    }
}
