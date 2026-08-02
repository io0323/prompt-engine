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
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.TokenCount
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationReport
import java.math.BigDecimal

class CompileUseCaseTest {
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
    fun `reports validationPassed=false when the report has ERROR findings`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val report =
            ValidationReport(
                listOf(
                    Finding(ruleId = "SchemaValidation", path = "$.x", severity = Severity.ERROR, message = "missing"),
                ),
            )
        val context =
            PipelineContext(
                request = request,
                mode = PipelineMode.COMPILE_ONLY,
                traceId = "trace-1",
                validationReport = report,
            )
        every { orchestrator.run(request, PipelineMode.COMPILE_ONLY, "trace-1") } returns context

        val useCase = CompileUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(CompileCommand(request, "trace-1"))

        result.validationPassed shouldBe false
        result.warningCount shouldBe 1
    }

    @Test
    fun `reports validationPassed=true when validationReport is absent`() {
        val orchestrator = mockk<PipelineOrchestrator>()
        val context = PipelineContext(request = request, mode = PipelineMode.COMPILE_ONLY, traceId = "trace-1")
        every { orchestrator.run(request, PipelineMode.COMPILE_ONLY, "trace-1") } returns context

        val useCase = CompileUseCase(orchestrator, PassthroughIdempotentCommandExecutor())
        val result = useCase.handle(CompileCommand(request, "trace-1"))

        result.validationPassed shouldBe true
        result.warningCount shouldBe 0
    }
}
