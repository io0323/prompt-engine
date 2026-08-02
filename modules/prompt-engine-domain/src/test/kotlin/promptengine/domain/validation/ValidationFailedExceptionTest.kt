package promptengine.domain.validation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** [ValidationFailedException]の契約テスト（設計書§13.3 `VALIDATION_FAILED`、ADR-0015決定4）。 */
class ValidationFailedExceptionTest {
    @Test
    fun `report を保持し メッセージにERROR件数を含む`() {
        val report =
            ValidationReport(
                listOf(
                    Finding("rule-a", "$.body", Severity.ERROR, "too long"),
                    Finding("rule-b", "$.body", Severity.WARNING, "style issue"),
                    Finding("rule-c", "$.body", Severity.ERROR, "missing placeholder"),
                ),
            )

        val exception = ValidationFailedException(report)

        exception.report shouldBe report
        exception.message shouldContain "2 error finding(s)"
    }
}
