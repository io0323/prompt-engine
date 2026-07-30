package promptengine.domain.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.TextNode

class OptimizationOutcomeTest {
    @Test
    fun `compiled contextBindings tokenEstimate report を保持する`() {
        val compiled = CompiledPrompt(listOf(TextNode("a")), emptyList(), emptyList(), emptyList())
        val contextBindings = ContextBindingSet.empty()
        val report = OptimizationReport.empty()

        val outcome = OptimizationOutcome(compiled, contextBindings, TokenCount(5), report)

        outcome.compiled shouldBe compiled
        outcome.contextBindings shouldBe contextBindings
        outcome.tokenEstimate shouldBe TokenCount(5)
        outcome.report shouldBe report
    }
}
