package promptengine.engine.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.shared.Cost
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode
import promptengine.domain.tokenizer.TokenizerPlugin
import java.math.BigDecimal

class ContextOptimizationRuleTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val rule = ContextOptimizationRule(tokenizer)
    private val profile = ModelProfile(TokenCount(8000), "approx-v1", Cost(BigDecimal.ZERO))

    private fun compiledPrompt(vararg nodes: promptengine.domain.template.ast.PromptAst): CompiledPrompt =
        CompiledPrompt(nodes.toList(), emptyList(), emptyList(), emptyList())

    @Test
    fun `常にapplicable`() {
        rule.applicable(
            compiledPrompt(),
            ContextBindingSet.empty(),
            profile,
            TokenCount(0),
            TokenCount(100),
        ) shouldBe true
    }

    @Test
    fun `参照されないスコープを除去する`() {
        val compiled = compiledPrompt(ExprNode(Expression(PropertyRef(listOf("context", "user", "name")))))
        val contextBindings =
            ContextBindingSet(
                mapOf(
                    "user.name" to "Alice",
                    "application.channel" to "web",
                ),
            )

        val result = rule.optimize(compiled, contextBindings, profile, TokenCount(0), TokenCount(100))

        result.contextBindings.values shouldBe mapOf("user.name" to "Alice")
        result.note.detail shouldBe "removed unreferenced context scopes: application"
    }

    @Test
    fun `参照されるスコープはそのまま保持しFindingを出さない`() {
        val compiled = compiledPrompt(ExprNode(Expression(PropertyRef(listOf("context", "user", "name")))))
        val contextBindings = ContextBindingSet(mapOf("user.name" to "Alice"))

        val result = rule.optimize(compiled, contextBindings, profile, TokenCount(0), TokenCount(100))

        result.contextBindings.values shouldBe mapOf("user.name" to "Alice")
        result.note.detail shouldBe "no unreferenced context scopes found"
    }

    @Test
    fun `除去したスコープがsensitive値でもdetailに実値を含めない`() {
        val compiled = compiledPrompt(TextNode("no references"))
        val contextBindings = ContextBindingSet(mapOf("user.apiKey" to SensitiveValue.of("sk-real-secret")))

        val result = rule.optimize(compiled, contextBindings, profile, TokenCount(0), TokenCount(100))

        result.note.detail shouldBe "removed unreferenced context scopes: user"
        result.note.detail.contains("sk-real-secret") shouldBe false
    }

    @Test
    fun `context以外のPropertyRefは無視する`() {
        val compiled = compiledPrompt(TextNode("plain"), ExprNode(Expression(PropertyRef(listOf("productName")))))
        val contextBindings = ContextBindingSet(mapOf("user.name" to "Alice"))

        val result = rule.optimize(compiled, contextBindings, profile, TokenCount(0), TokenCount(100))

        result.contextBindings.values shouldBe emptyMap()
    }

    @Test
    fun `contextの後にscope名しか無い短いPropertyRefは参照とみなさない`() {
        // path=["context","user"]はscope名のみでpath未満(size<3)のため、
        // "user"スコープを参照しているとは扱わずuser scopeも除去対象になる
        val compiled = compiledPrompt(ExprNode(Expression(PropertyRef(listOf("context", "user")))))
        val contextBindings = ContextBindingSet(mapOf("user.name" to "Alice"))

        val result = rule.optimize(compiled, contextBindings, profile, TokenCount(0), TokenCount(100))

        result.contextBindings.values shouldBe emptyMap()
        result.note.detail shouldBe "removed unreferenced context scopes: user"
    }
}
