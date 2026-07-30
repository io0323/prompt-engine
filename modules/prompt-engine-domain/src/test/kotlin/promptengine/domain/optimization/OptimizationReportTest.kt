package promptengine.domain.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.TokenCount

class OptimizationReportTest {
    @Test
    fun `appliedRules truncations を保持する`() {
        val note = OptimizationNote("TokenOptimization", TokenCount(10), "detail")
        val truncation = TruncationNote("conversation", TokenCount(100), TokenCount(40), "summary")

        val report = OptimizationReport(listOf(note), listOf(truncation))

        report.appliedRules shouldBe listOf(note)
        report.truncations shouldBe listOf(truncation)
    }

    @Test
    fun `truncationsの既定値は空リスト`() {
        val report = OptimizationReport(emptyList())

        report.truncations shouldBe emptyList()
    }

    @Test
    fun `emptyはappliedRules truncations共に空`() {
        val report = OptimizationReport.empty()

        report.appliedRules shouldBe emptyList()
        report.truncations shouldBe emptyList()
    }
}
