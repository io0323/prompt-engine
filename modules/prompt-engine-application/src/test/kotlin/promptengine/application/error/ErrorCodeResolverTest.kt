package promptengine.application.error

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.pipeline.StageErrorMapper
import promptengine.domain.prompt.ArchiveRequiresForceException
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.IdempotencyKeyConflictException
import promptengine.domain.shared.IdempotencyKeyInProgressException
import promptengine.domain.shared.OptimisticLockConflictException
import promptengine.domain.shared.SemVer
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationFailedException
import promptengine.domain.validation.ValidationReport

class ErrorCodeResolverTest {
    @Test
    fun `resolve はprompt-InvalidStateTransitionExceptionをINVALID_STATE_TRANSITIONへ変換する`() {
        val ex = promptengine.domain.prompt.InvalidStateTransitionException("Draft", "publish")

        ErrorCodeResolver.resolve(ex) shouldBe ErrorCodeResolver.INVALID_STATE_TRANSITION
    }

    @Test
    fun `resolve はshared-InvalidStateTransitionExceptionをINVALID_STATE_TRANSITIONへ変換する`() {
        val ex = promptengine.domain.shared.InvalidStateTransitionException("Draft", "publish")

        ErrorCodeResolver.resolve(ex) shouldBe ErrorCodeResolver.INVALID_STATE_TRANSITION
    }

    @Test
    fun `resolve はArchiveRequiresForceExceptionをINVALID_STATE_TRANSITIONへ変換する`() {
        val ex = ArchiveRequiresForceException(PromptKey("support/faq-answer"), SemVer(1, 0, 0))

        ErrorCodeResolver.resolve(ex) shouldBe ErrorCodeResolver.INVALID_STATE_TRANSITION
    }

    @Test
    fun `resolve はIdempotencyKeyConflictExceptionをIDEMPOTENCY_KEY_CONFLICTへ変換する`() {
        val ex = IdempotencyKeyConflictException("idem-1")

        ErrorCodeResolver.resolve(ex) shouldBe ErrorCodeResolver.IDEMPOTENCY_KEY_CONFLICT
    }

    @Test
    fun `resolve はIdempotencyKeyInProgressExceptionをIDEMPOTENCY_KEY_IN_PROGRESSへ変換する`() {
        val ex = IdempotencyKeyInProgressException("idem-1")

        ErrorCodeResolver.resolve(ex) shouldBe ErrorCodeResolver.IDEMPOTENCY_KEY_IN_PROGRESS
    }

    @Test
    fun `resolve はOptimisticLockConflictExceptionをVERSION_CONFLICTへ変換する`() {
        class FakeConflict : OptimisticLockConflictException("conflict")
        val ex = FakeConflict()

        ErrorCodeResolver.resolve(ex) shouldBe ErrorCodeResolver.VERSION_CONFLICT
    }

    @Test
    fun `resolve はStageErrorMapper対象の例外をそのまま委譲する`() {
        val ex = ValidationFailedException(ValidationReport.empty())

        ErrorCodeResolver.resolve(ex) shouldBe StageErrorMapper.VALIDATION_FAILED
    }

    @Test
    fun `resolve はどのケースにも一致しない例外をStageErrorMapperのINTERNAL_ERRORへフォールバックする`() {
        val ex = RuntimeException("unexpected")

        ErrorCodeResolver.resolve(ex) shouldBe StageErrorMapper.INTERNAL_ERROR
    }

    @Test
    fun `detailsFor はValidationFailedExceptionのfindingsをErrorDetailへ変換する`() {
        val report =
            ValidationReport(
                listOf(
                    Finding(ruleId = "R1", path = "$.x", severity = Severity.ERROR, message = "bad"),
                    Finding(ruleId = "R2", path = "$.y", severity = Severity.WARNING, message = "warn"),
                ),
            )
        val ex = ValidationFailedException(report)

        val details = ErrorCodeResolver.detailsFor(ex)

        details shouldBe
            listOf(
                ErrorDetail("R1", "$.x", "ERROR", "bad"),
                ErrorDetail("R2", "$.y", "WARNING", "warn"),
            )
    }

    @Test
    fun `detailsFor はValidationFailedException以外は空リストを返す`() {
        ErrorCodeResolver.detailsFor(RuntimeException("unexpected")) shouldBe emptyList()
    }
}
