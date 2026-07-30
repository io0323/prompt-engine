package promptengine.domain.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ValidationReportTest {
    @Test
    fun `ERROR Findingが1件も無ければ hasErrors は false`() {
        val report =
            ValidationReport(
                listOf(
                    Finding("Rule", "$.x", Severity.WARNING, "warn"),
                    Finding("Rule", "$.y", Severity.INFO, "info"),
                ),
            )

        report.hasErrors shouldBe false
    }

    @Test
    fun `ERROR Findingが1件でもあれば hasErrors は true`() {
        val report =
            ValidationReport(
                listOf(
                    Finding("Rule", "$.x", Severity.WARNING, "warn"),
                    Finding("Rule", "$.y", Severity.ERROR, "error"),
                ),
            )

        report.hasErrors shouldBe true
    }

    @Test
    fun `empty はFindingを持たずhasErrorsはfalse`() {
        val report = ValidationReport.empty()

        report.findings shouldBe emptyList()
        report.hasErrors shouldBe false
    }
}
