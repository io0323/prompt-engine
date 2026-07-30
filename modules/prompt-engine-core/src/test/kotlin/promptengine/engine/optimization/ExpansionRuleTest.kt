package promptengine.engine.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelCapability
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.shared.Cost
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.TextNode
import java.math.BigDecimal

class ExpansionRuleTest {
    private val rule = ExpansionRule("be precise")

    private fun compiledPrompt(vararg nodes: promptengine.domain.template.ast.PromptAst): CompiledPrompt =
        CompiledPrompt(nodes.toList(), emptyList(), emptyList(), emptyList())

    @Test
    fun `WEAK_INSTRUCTION_FOLLOWINGを持たないprofileではapplicableでない`() {
        val profile = ModelProfile(TokenCount(8000), "approx-v1", Cost(BigDecimal.ZERO))

        rule.applicable(
            compiledPrompt(),
            ContextBindingSet.empty(),
            profile,
            TokenCount(0),
            TokenCount(100),
        ) shouldBe false
    }

    @Test
    fun `WEAK_INSTRUCTION_FOLLOWINGを持つprofileではapplicable`() {
        val profile =
            ModelProfile(
                TokenCount(8000),
                "approx-v1",
                Cost(BigDecimal.ZERO),
                setOf(ModelCapability.WEAK_INSTRUCTION_FOLLOWING),
            )

        rule.applicable(
            compiledPrompt(),
            ContextBindingSet.empty(),
            profile,
            TokenCount(0),
            TokenCount(100),
        ) shouldBe true
    }

    @Test
    fun `既存のSYSTEMブロックの末尾へ追記する`() {
        val profile =
            ModelProfile(
                TokenCount(8000),
                "approx-v1",
                Cost(BigDecimal.ZERO),
                setOf(ModelCapability.WEAK_INSTRUCTION_FOLLOWING),
            )
        val compiled = compiledPrompt(BlockNode(BlockRole.SYSTEM, listOf(TextNode("base instruction"))))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        val block = result.compiled.body.single() as BlockNode
        block.role shouldBe BlockRole.SYSTEM
        block.body shouldBe listOf(TextNode("base instruction"), TextNode("\n\nbe precise"))
    }

    @Test
    fun `SYSTEMブロックが無ければ本文先頭に新規追加する`() {
        val profile =
            ModelProfile(
                TokenCount(8000),
                "approx-v1",
                Cost(BigDecimal.ZERO),
                setOf(ModelCapability.WEAK_INSTRUCTION_FOLLOWING),
            )
        val compiled = compiledPrompt(BlockNode(BlockRole.USER, listOf(TextNode("hello"))))

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        result.compiled.body shouldBe
            listOf(
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("be precise"))),
                BlockNode(BlockRole.USER, listOf(TextNode("hello"))),
            )
    }

    @Test
    fun `BlockNode以外のノードが混在してもSYSTEMブロック探索は継続する`() {
        val profile =
            ModelProfile(
                TokenCount(8000),
                "approx-v1",
                Cost(BigDecimal.ZERO),
                setOf(ModelCapability.WEAK_INSTRUCTION_FOLLOWING),
            )
        val compiled =
            compiledPrompt(
                TextNode("intro"),
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("base instruction"))),
            )

        val result = rule.optimize(compiled, ContextBindingSet.empty(), profile, TokenCount(0), TokenCount(100))

        val block = result.compiled.body[1] as BlockNode
        block.body shouldBe listOf(TextNode("base instruction"), TextNode("\n\nbe precise"))
    }
}
