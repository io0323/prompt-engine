package promptengine.domain.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FindingTest {
    @Test
    fun `ruleId path severity message を保持する`() {
        val finding =
            Finding(
                ruleId = "SchemaValidation",
                path = "$.parameters.productName",
                severity = Severity.ERROR,
                message = "value does not match declared type",
            )

        finding.ruleId shouldBe "SchemaValidation"
        finding.path shouldBe "$.parameters.productName"
        finding.severity shouldBe Severity.ERROR
        finding.message shouldBe "value does not match declared type"
    }

    @Test
    fun `同一内容のFindingは構造的に等しい`() {
        val a = Finding("Rule", "$.x", Severity.WARNING, "message")
        val b = Finding("Rule", "$.x", Severity.WARNING, "message")

        a shouldBe b
    }
}
