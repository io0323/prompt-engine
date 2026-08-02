package promptengine.application.pipeline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CircularDependencyException
import promptengine.domain.composition.CompositionDepthExceededException
import promptengine.domain.composition.FragmentReferenceNotFoundException
import promptengine.domain.composition.TemplateReferenceNotFoundException
import promptengine.domain.context.ContextUnavailableException
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.optimization.TokenBudgetExceededException
import promptengine.domain.parsing.ParseFailedException
import promptengine.domain.pipeline.InvalidPipelineRequestException
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderFailedException
import promptengine.domain.shared.TokenCount
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationFailedException
import promptengine.domain.validation.ValidationReport
import promptengine.domain.variable.VariableUnresolvedException

/**
 * [StageErrorMapper]の純粋単体テスト（ADR-0015決定4の表、設計書§13.3）。
 * 型→エラーコードの写像1箇所への集約が、表の全11コード+フォールバックについて
 * 正しいことを検証する。
 */
class StageErrorMapperTest {
    @Test
    fun `PromptVersionNotFoundException は PROMPT_NOT_FOUND`() {
        val exception = PromptVersionNotFoundException.forKey(PromptKey("support/x"))
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.PROMPT_NOT_FOUND
    }

    @Test
    fun `TemplateReferenceNotFoundException は TEMPLATE_NOT_FOUND`() {
        val exception = TemplateReferenceNotFoundException(TemplateKey("templates/x"), VersionRange.Latest)
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.TEMPLATE_NOT_FOUND
    }

    @Test
    fun `CircularDependencyException は CIRCULAR_DEPENDENCY`() {
        val exception = CircularDependencyException(listOf("templates/a", "templates/b"))
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.CIRCULAR_DEPENDENCY
    }

    @Test
    fun `FragmentReferenceNotFoundException は FRAGMENT_NOT_FOUND`() {
        val exception = FragmentReferenceNotFoundException(FragmentKey("fragments/x"), VersionRange.Latest)
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.FRAGMENT_NOT_FOUND
    }

    @Test
    fun `VariableUnresolvedException は VARIABLE_UNRESOLVED`() {
        val exception = VariableUnresolvedException(listOf("x"))
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.VARIABLE_UNRESOLVED
    }

    @Test
    fun `ContextUnavailableException は CONTEXT_UNAVAILABLE`() {
        val exception = ContextUnavailableException(listOf("system.now"))
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.CONTEXT_UNAVAILABLE
    }

    @Test
    fun `ValidationFailedException は VALIDATION_FAILED`() {
        val report = ValidationReport(listOf(Finding("rule", "$.body", Severity.ERROR, "invalid")))
        val exception = ValidationFailedException(report)
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.VALIDATION_FAILED
    }

    @Test
    fun `TokenBudgetExceededException は TOKEN_BUDGET_EXCEEDED`() {
        val exception = TokenBudgetExceededException(TokenCount(10), TokenCount(5))
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.TOKEN_BUDGET_EXCEEDED
    }

    @Test
    fun `RenderFailedException は RENDER_ERROR`() {
        val exception = RenderFailedException("no OutputFormatter registered for outputFormat=JSON")
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.RENDER_ERROR
    }

    @Test
    fun `ExecutionFailedException は EXECUTION_FAILED`() {
        val exception = ExecutionFailedException(ExecutionErrorType.SERVER_ERROR, 0)
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.EXECUTION_FAILED
    }

    @Test
    fun `ParseFailedException は PARSE_FAILED`() {
        val exception = ParseFailedException(OutputFormat.TEXT, "bad")
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.PARSE_FAILED
    }

    @Test
    fun `InvalidPipelineRequestException は INVALID_REQUEST`() {
        val exception = InvalidPipelineRequestException("executionPolicy is required")
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.INVALID_REQUEST
    }

    @Test
    fun `IllegalStateException は RenderFailedExceptionではないため RENDER_ERROR にならず INTERNAL_ERROR になる`() {
        // ステージ自身のcheckNotNull（前段ステージ未実行の防御コード）が投げる例外を模する。
        // RenderingStage由来であってもRenderFailedException型でなければRENDER_ERRORにはならない
        // ことを固定する（ADR-0015決定4修正）。
        val exception = IllegalStateException("RenderingStage requires compiled (Stage 2 Merge must run first)")
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.INTERNAL_ERROR
    }

    @Test
    fun `未分類の例外型は常に INTERNAL_ERROR`() {
        val exception = CompositionDepthExceededException(10)
        StageErrorMapper.errorCodeFor(exception) shouldBe StageErrorMapper.INTERNAL_ERROR
    }
}
