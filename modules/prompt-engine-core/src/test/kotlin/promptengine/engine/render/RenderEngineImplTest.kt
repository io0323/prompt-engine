package promptengine.engine.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.BindingSet

class RenderEngineImplTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val engine = RenderEngineImpl(DefaultTemplateEngine(), tokenizer)

    private fun compiledPrompt(vararg nodes: promptengine.domain.template.ast.PromptAst): CompiledPrompt =
        CompiledPrompt(nodes.toList(), emptyList(), emptyList(), emptyList())

    @Test
    fun `同一入力から100回renderしてもrenderHashは全て一致する`() {
        val compiled =
            compiledPrompt(
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("you are helpful"))),
                BlockNode(BlockRole.USER, listOf(ExprNode(Expression(PropertyRef(listOf("question")))))),
            )
        val bindings = BindingSet(mapOf("question" to "what is 2+2?"))

        val hashes =
            (1..100).map {
                engine.render(compiled, bindings, ContextBindingSet.empty(), OutputFormat.TEXT).renderHash
            }.toSet()

        hashes.size shouldBe 1
    }

    @Test
    fun `バインディングの投入順を変えてもrenderHashは変わらない`() {
        val compiled =
            compiledPrompt(
                ExprNode(Expression(PropertyRef(listOf("a")))),
                ExprNode(Expression(PropertyRef(listOf("b")))),
            )
        val bindingsOrderA = BindingSet(linkedMapOf("a" to "1", "b" to "2"))
        val bindingsOrderB = BindingSet(linkedMapOf("b" to "2", "a" to "1"))

        val hashA = engine.render(compiled, bindingsOrderA, ContextBindingSet.empty(), OutputFormat.TEXT).renderHash
        val hashB = engine.render(compiled, bindingsOrderB, ContextBindingSet.empty(), OutputFormat.TEXT).renderHash

        hashA shouldBe hashB
    }

    @Test
    fun `outputFormatが異なればrenderHashも異なる`() {
        val compiled = compiledPrompt(TextNode("hello"))

        val jsonHash =
            engine.render(
                compiled,
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.JSON,
            ).renderHash
        val textHash =
            engine.render(
                compiled,
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.TEXT,
            ).renderHash

        jsonHash shouldNotBe textHash
    }

    @Test
    fun `内容が異なればrenderHashも異なる`() {
        val hash1 =
            engine.render(
                compiledPrompt(TextNode("a")),
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.TEXT,
            ).renderHash
        val hash2 =
            engine.render(
                compiledPrompt(TextNode("b")),
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.TEXT,
            ).renderHash

        hash1 shouldNotBe hash2
    }

    @Test
    fun `CRLFとLFは同じrenderHashになる`() {
        val crlfHash =
            engine.render(
                compiledPrompt(TextNode("line1\r\nline2")),
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.TEXT,
            )
                .renderHash
        val lfHash =
            engine.render(
                compiledPrompt(TextNode("line1\nline2")),
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.TEXT,
            )
                .renderHash

        crlfHash shouldBe lfHash
    }

    @Test
    fun `行末の空白の有無は同じrenderHashになる`() {
        val withTrailing =
            engine.render(
                compiledPrompt(TextNode("line1   \nline2")),
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.TEXT,
            )
                .renderHash
        val withoutTrailing =
            engine.render(
                compiledPrompt(TextNode("line1\nline2")),
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.TEXT,
            )
                .renderHash

        withTrailing shouldBe withoutTrailing
    }

    @Test
    fun `sensitive値はrenderHashの計算に寄与する`() {
        val bindingsA = BindingSet(mapOf("key" to SensitiveValue.of("secret-a")))
        val bindingsB = BindingSet(mapOf("key" to SensitiveValue.of("secret-b")))
        val compiled = compiledPrompt(ExprNode(Expression(PropertyRef(listOf("key")))))

        val hashA = engine.render(compiled, bindingsA, ContextBindingSet.empty(), OutputFormat.TEXT).renderHash
        val hashB = engine.render(compiled, bindingsB, ContextBindingSet.empty(), OutputFormat.TEXT).renderHash

        hashA shouldNotBe hashB
    }

    @Test
    fun `sensitive値はcontentに生値のまま残りマスクされない`() {
        val bindings = BindingSet(mapOf("key" to SensitiveValue.of("sk-real-secret")))
        val compiled = compiledPrompt(ExprNode(Expression(PropertyRef(listOf("key")))))

        val result = engine.render(compiled, bindings, ContextBindingSet.empty(), OutputFormat.TEXT)

        result.messages.single().content shouldBe "sk-real-secret"
    }

    @Test
    fun `tokenEstimateはmessages全体の文字数見積り`() {
        val compiled = compiledPrompt(TextNode("hello"))

        val result = engine.render(compiled, BindingSet.empty(), ContextBindingSet.empty(), OutputFormat.TEXT)

        result.tokenEstimate shouldBe TokenCount(5)
    }

    @Test
    fun `区切り文字と紛らわしい内容でもmessages構成が異なればrenderHashは異なる`() {
        // 単純な区切り文字（例: 空白）でフィールドを連結すると、下記2構成は区切り文字方式次第で
        // バイト列が一致しうる（"SYSTEM"+区切り+"hi"+区切り+"USER"+区切り+"there"+区切り
        // vs "SYSTEM"+区切り+"hi USER there"+区切り）。長さプレフィックス方式ならフィールド境界が
        // 曖昧にならないため、この2構成のrenderHashは必ず異なる。
        val twoMessages =
            compiledPrompt(
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("hi"))),
                BlockNode(BlockRole.USER, listOf(TextNode("there"))),
            )
        val oneMessageWithRoleLikeContent =
            compiledPrompt(BlockNode(BlockRole.SYSTEM, listOf(TextNode("hi USER there"))))

        val hashTwoMessages =
            engine.render(twoMessages, BindingSet.empty(), ContextBindingSet.empty(), OutputFormat.TEXT).renderHash
        val hashOneMessage =
            engine.render(
                oneMessageWithRoleLikeContent,
                BindingSet.empty(),
                ContextBindingSet.empty(),
                OutputFormat.TEXT,
            ).renderHash

        hashTwoMessages shouldNotBe hashOneMessage
    }

    @Test
    fun `messages roleとoutputFormatを保持する`() {
        val compiled = compiledPrompt(BlockNode(BlockRole.ASSISTANT, listOf(TextNode("hi"))))

        val result = engine.render(compiled, BindingSet.empty(), ContextBindingSet.empty(), OutputFormat.MARKDOWN)

        result.messages.single().role shouldBe MessageRole.ASSISTANT
        result.outputFormat shouldBe OutputFormat.MARKDOWN
    }
}
