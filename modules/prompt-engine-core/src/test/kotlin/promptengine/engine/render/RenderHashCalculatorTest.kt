package promptengine.engine.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import promptengine.domain.render.MessageRole
import promptengine.domain.render.ModelHints
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin

class RenderHashCalculatorTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val messages = listOf(RenderedMessage(MessageRole.SYSTEM, "hi"))

    @Test
    fun `同一入力からは常に同一のhashを返す`() {
        val a = RenderHashCalculator.build(messages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)
        val b = RenderHashCalculator.build(messages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)

        a.renderHash shouldBe b.renderHash
    }

    @Test
    fun `engineIdが異なればhashも異なる`() {
        val a = RenderHashCalculator.build(messages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)
        val b = RenderHashCalculator.build(messages, OutputFormat.TEXT, "pe-repair/1", tokenizer)

        a.renderHash shouldNotBe b.renderHash
    }

    @Test
    fun `engineVersionが異なればhashも異なる`() {
        val a = RenderHashCalculator.build(messages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer, engineVersion = "1")
        val b = RenderHashCalculator.build(messages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer, engineVersion = "2")

        a.renderHash shouldNotBe b.renderHash
    }

    // ---- 正規化バイパス不可能性: build()は未正規化のmessagesしか受け取れず、常に正規化してから
    // hashを算出する（normalize/computeがprivateであり、正規化を経由しないhash算出経路が
    // 存在しない構造。前回レビュー指摘の再確認） ----

    @Test
    fun `未正規化のCRLFを渡してもbuildが返すmessagesは正規化されLF等価な入力とhashが一致する`() {
        val crlfMessages = listOf(RenderedMessage(MessageRole.SYSTEM, "line1\r\nline2"))
        val lfMessages = listOf(RenderedMessage(MessageRole.SYSTEM, "line1\nline2"))

        val crlfResult = RenderHashCalculator.build(crlfMessages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)
        val lfResult = RenderHashCalculator.build(lfMessages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)

        crlfResult.messages.single().content shouldBe "line1\nline2"
        crlfResult.renderHash shouldBe lfResult.renderHash
    }

    @Test
    fun `buildが返すmessagesを再度buildへ渡しても同じrenderHashになる 正規化は冪等`() {
        val trailingWhitespace = listOf(RenderedMessage(MessageRole.SYSTEM, "line1   \nline2"))

        val result = RenderHashCalculator.build(trailingWhitespace, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)
        val rebuilt = RenderHashCalculator.build(result.messages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)

        result.renderHash shouldBe rebuilt.renderHash
    }

    @Test
    fun `tokenEstimateは正規化後のmessagesから算出される`() {
        val trailingWhitespace = listOf(RenderedMessage(MessageRole.SYSTEM, "hi   "))

        val result = RenderHashCalculator.build(trailingWhitespace, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)

        result.tokenEstimate shouldBe TokenCount(2)
    }

    // ---- modelHints（ADR-0035決定5）: renderHashの算出には影響しないが、結果のRenderedPromptには渡した値がそのまま乗る ----

    @Test
    fun `modelHintsが異なってもrenderHashは変わらない`() {
        val withoutHints = RenderHashCalculator.build(messages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)
        val withHints =
            RenderHashCalculator.build(
                messages,
                OutputFormat.TEXT,
                "pe-tmpl/1",
                tokenizer,
                modelHints = ModelHints(temperature = 0.9),
            )

        withHints.renderHash shouldBe withoutHints.renderHash
    }

    @Test
    fun `渡したmodelHintsは結果のRenderedPromptにそのまま乗る`() {
        val result =
            RenderHashCalculator.build(
                messages,
                OutputFormat.TEXT,
                "pe-tmpl/1",
                tokenizer,
                modelHints = ModelHints(temperature = 0.0),
            )

        result.modelHints shouldBe ModelHints(temperature = 0.0)
    }

    @Test
    fun `modelHintsを省略するとnullになる`() {
        val result = RenderHashCalculator.build(messages, OutputFormat.TEXT, "pe-tmpl/1", tokenizer)

        result.modelHints shouldBe null
    }
}
