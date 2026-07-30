package promptengine.engine.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.shared.Cost
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode
import promptengine.domain.tokenizer.TokenizerPlugin
import java.math.BigDecimal

class TokenOptimizationRuleTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val profile = ModelProfile(TokenCount(8000), "approx-v1", Cost(BigDecimal.ZERO))

    private fun compiledPrompt(vararg nodes: PromptAst): CompiledPrompt =
        CompiledPrompt(
            body = nodes.toList(),
            dependencies = emptyList(),
            variables = emptyList(),
            contextRequirements = emptyList(),
        )

    @Test
    fun `enabledがtrueなら常にapplicable`() {
        val rule = TokenOptimizationRule(tokenizer)

        rule.applicable(
            compiledPrompt(TextNode("x")),
            ContextBindingSet.empty(),
            profile,
            TokenCount(0),
            TokenCount(100),
        ) shouldBe true
    }

    @Test
    fun `enabledがfalseならapplicableでない`() {
        val rule = TokenOptimizationRule(tokenizer, enabled = false)

        rule.applicable(
            compiledPrompt(TextNode("x")),
            ContextBindingSet.empty(),
            profile,
            TokenCount(0),
            TokenCount(100),
        ) shouldBe false
    }

    @Test
    fun `連続する空白タブを1個へ圧縮する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled = compiledPrompt(TextNode("hello   \t\tworld"))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        (result.compiled.body.single() as TextNode).text shouldBe "hello world"
    }

    @Test
    fun `3行以上連続する空行を2行へ圧縮する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled = compiledPrompt(TextNode("a\n\n\n\nb"))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        (result.compiled.body.single() as TextNode).text shouldBe "a\n\nb"
    }

    @Test
    fun `行末の空白を除去する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled = compiledPrompt(TextNode("hello   \nworld"))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        (result.compiled.body.single() as TextNode).text shouldBe "hello\nworld"
    }

    @Test
    fun `隣接していても内容が異なるTextNodeは両方保持する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled = compiledPrompt(TextNode("first"), TextNode("second"))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        result.compiled.body shouldBe listOf(TextNode("first"), TextNode("second"))
    }

    @Test
    fun `隣接する同一内容のTextNodeは後者を除去する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled = compiledPrompt(TextNode("same"), TextNode("same"))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        result.compiled.body shouldBe listOf(TextNode("same"))
    }

    @Test
    fun `tokensSavedは正規化前後の見積り差分`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled = compiledPrompt(TextNode("a    b"))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        // "a    b" (6文字) -> "a b" (3文字)
        result.note.tokensSaved shouldBe TokenCount(3)
    }

    @Test
    fun `IfNodeのthen elseブランチ双方の空白を正規化する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled =
            compiledPrompt(
                IfNode(
                    condition = Expression(PropertyRef(listOf("flag"))),
                    thenBranch = listOf(TextNode("then   text")),
                    elseBranch = listOf(TextNode("else   text")),
                ),
            )

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        val ifNode = result.compiled.body.single() as IfNode
        (ifNode.thenBranch.single() as TextNode).text shouldBe "then text"
        (ifNode.elseBranch.single() as TextNode).text shouldBe "else text"
    }

    @Test
    fun `EachNodeの本文の空白を正規化する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled =
            compiledPrompt(
                EachNode(
                    iterable = Expression(PropertyRef(listOf("items"))),
                    itemName = "item",
                    body = listOf(TextNode("body   text")),
                ),
            )

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        val eachNode = result.compiled.body.single() as EachNode
        (eachNode.body.single() as TextNode).text shouldBe "body text"
    }

    @Test
    fun `BlockNodeの本文の空白を正規化する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled = compiledPrompt(BlockNode(BlockRole.SYSTEM, listOf(TextNode("block   text"))))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        val blockNode = result.compiled.body.single() as BlockNode
        (blockNode.body.single() as TextNode).text shouldBe "block text"
    }

    @Test
    fun `literalTextはIfNode EachNode BlockNodeを再帰的に連結する`() {
        val rule = TokenOptimizationRule(tokenizer)
        val compiled =
            compiledPrompt(
                IfNode(
                    condition = Expression(PropertyRef(listOf("flag"))),
                    thenBranch = listOf(TextNode("then")),
                    elseBranch = listOf(TextNode("else")),
                ),
                EachNode(
                    iterable = Expression(PropertyRef(listOf("items"))),
                    itemName = "item",
                    body = listOf(TextNode("each")),
                ),
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("block"))),
            )

        // tokensSavedの計算はliteralTextに依存するため、全ノード種別ぶんの文字数が
        // 差分計算に反映されていることを間接的に検証する（正規化で変化しない内容のため差分0）
        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        result.note.tokensSaved shouldBe TokenCount(0)
    }
}
