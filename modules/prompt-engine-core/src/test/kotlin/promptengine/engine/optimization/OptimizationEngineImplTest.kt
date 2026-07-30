package promptengine.engine.optimization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.optimization.TokenBudgetExceededException
import promptengine.domain.shared.Cost
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.BindingSet
import java.math.BigDecimal

class OptimizationEngineImplTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val profile = ModelProfile(TokenCount(8000), "approx-v1", Cost(BigDecimal.ZERO))

    @Test
    fun `適用されたRuleをすべてappliedRulesに記録する`() {
        val engine = OptimizationEngineImpl(listOf(TokenOptimizationRule(tokenizer)), tokenizer)
        val compiled = CompiledPrompt(listOf(TextNode("a    b")), emptyList(), emptyList(), emptyList())

        val outcome =
            engine.optimize(compiled, BindingSet.empty(), ContextBindingSet.empty(), profile, TokenCount(100))

        outcome.report.appliedRules.map { it.ruleId } shouldBe listOf("TokenOptimization")
        (outcome.compiled.body.single() as TextNode).text shouldBe "a b"
    }

    @Test
    fun `最適化後もbudgetを超えるとTokenBudgetExceededExceptionを投げる`() {
        val engine = OptimizationEngineImpl(emptyList(), tokenizer)
        val compiled =
            CompiledPrompt(listOf(TextNode("this text is way too long")), emptyList(), emptyList(), emptyList())

        val exception =
            shouldThrow<TokenBudgetExceededException> {
                engine.optimize(compiled, BindingSet.empty(), ContextBindingSet.empty(), profile, TokenCount(1))
            }

        exception.budget shouldBe TokenCount(1)
    }

    @Test
    fun `budget内に収まればTokenBudgetExceededExceptionを投げない`() {
        val engine = OptimizationEngineImpl(emptyList(), tokenizer)
        val compiled = CompiledPrompt(listOf(TextNode("ok")), emptyList(), emptyList(), emptyList())

        val outcome =
            engine.optimize(compiled, BindingSet.empty(), ContextBindingSet.empty(), profile, TokenCount(100))

        outcome.report.appliedRules shouldBe emptyList()
    }

    @Test
    fun `Rule適用後の見積りを使って後続Ruleのapplicableを判定する`() {
        // "a    a"(6トークン)はbudget=4を超えるためCompressionも一見適用対象に見えるが、
        // TokenOptimizationが先に"a a"(3トークン)へ縮小するため、Compressionはもはや不要になる
        val contextBindings = ContextBindingSet(mapOf("conversation.messages" to listOf("m1")))
        val engine =
            OptimizationEngineImpl(
                listOf(TokenOptimizationRule(tokenizer), CompressionRule(tokenizer)),
                tokenizer,
            )
        val compiled = CompiledPrompt(listOf(TextNode("a    a")), emptyList(), emptyList(), emptyList())

        val outcome = engine.optimize(compiled, BindingSet.empty(), contextBindings, profile, TokenCount(4))

        outcome.report.appliedRules.map { it.ruleId } shouldBe listOf("TokenOptimization")
        outcome.contextBindings shouldBe contextBindings
    }

    @Test
    fun `truncationsをOptimizationReportへ集約する`() {
        // AstTextEstimatorはASTから実際に参照されるcontext値のみを見積りに含めるため、
        // conversation.messagesを直接参照するExprNodeを本文に置く
        val contextBindings = ContextBindingSet(mapOf("conversation.messages" to listOf("aaaaaaaaaa")))
        val engine = OptimizationEngineImpl(listOf(CompressionRule(tokenizer)), tokenizer)
        val compiled =
            CompiledPrompt(
                listOf(ExprNode(Expression(PropertyRef(listOf("context", "conversation", "messages"))))),
                emptyList(),
                emptyList(),
                emptyList(),
            )

        val outcome = engine.optimize(compiled, BindingSet.empty(), contextBindings, profile, TokenCount(2))

        outcome.report.truncations.single().scope shouldBe "conversation"
        @Suppress("UNCHECKED_CAST")
        val remaining = outcome.contextBindings.values["conversation.messages"] as List<String>
        remaining shouldBe emptyList()
    }
}
