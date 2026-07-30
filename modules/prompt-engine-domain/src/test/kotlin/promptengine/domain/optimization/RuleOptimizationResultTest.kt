package promptengine.domain.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.TextNode

class RuleOptimizationResultTest {
    private val compiled = CompiledPrompt(listOf(TextNode("a")), emptyList(), emptyList(), emptyList())

    @Test
    fun `compiled contextBindings note truncations を保持する`() {
        val note = OptimizationNote("Compression", TokenCount(5), "detail")
        val truncation = TruncationNote("conversation", TokenCount(100), TokenCount(40), "summary")

        val result = RuleOptimizationResult(compiled, ContextBindingSet.empty(), note, listOf(truncation))

        result.compiled shouldBe compiled
        result.contextBindings shouldBe ContextBindingSet.empty()
        result.note shouldBe note
        result.truncations shouldBe listOf(truncation)
    }

    @Test
    fun `truncationsの既定値は空リスト`() {
        val note = OptimizationNote("TokenOptimization", TokenCount(0), "detail")

        val result = RuleOptimizationResult(compiled, ContextBindingSet.empty(), note)

        result.truncations shouldBe emptyList()
    }

    @Test
    fun `構築後に呼出元のMutableListを変更してもtruncationsは影響を受けない`() {
        val note = OptimizationNote("Compression", TokenCount(5), "detail")
        val truncation = TruncationNote("conversation", TokenCount(100), TokenCount(40), "summary")
        val truncationsSource = mutableListOf(truncation)

        val result = RuleOptimizationResult(compiled, ContextBindingSet.empty(), note, truncationsSource)
        truncationsSource.clear()

        result.truncations shouldBe listOf(truncation)
    }
}
