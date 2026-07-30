package promptengine.domain.render

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.TokenCount

class RenderedPromptTest {
    @Test
    fun `messages outputFormat tokenEstimate renderHash を保持する`() {
        val prompt =
            RenderedPrompt(
                messages = listOf(RenderedMessage(MessageRole.USER, "hello")),
                outputFormat = OutputFormat.TEXT,
                tokenEstimate = TokenCount(2),
                renderHash = "abc123",
            )

        prompt.messages shouldBe listOf(RenderedMessage(MessageRole.USER, "hello"))
        prompt.outputFormat shouldBe OutputFormat.TEXT
        prompt.tokenEstimate shouldBe TokenCount(2)
        prompt.renderHash shouldBe "abc123"
    }

    @Test
    fun `renderHashが空文字だと例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            RenderedPrompt(
                messages = listOf(RenderedMessage(MessageRole.USER, "hello")),
                outputFormat = OutputFormat.TEXT,
                tokenEstimate = TokenCount(0),
                renderHash = "",
            )
        }
    }

    @Test
    fun `messagesが空だと例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            RenderedPrompt(
                messages = emptyList(),
                outputFormat = OutputFormat.TEXT,
                tokenEstimate = TokenCount(0),
                renderHash = "abc123",
            )
        }
    }

    @Test
    fun `構築後に呼出元のMutableListを変更してもmessagesは影響を受けない`() {
        val source = mutableListOf(RenderedMessage(MessageRole.USER, "hello"))
        val prompt = RenderedPrompt(source, OutputFormat.TEXT, TokenCount(2), "abc123")

        source += RenderedMessage(MessageRole.SYSTEM, "injected")

        prompt.messages shouldBe listOf(RenderedMessage(MessageRole.USER, "hello"))
    }
}
