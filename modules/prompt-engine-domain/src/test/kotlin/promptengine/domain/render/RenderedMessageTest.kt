package promptengine.domain.render

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RenderedMessageTest {
    @Test
    fun `role content を保持する`() {
        val message = RenderedMessage(MessageRole.SYSTEM, "you are a helpful assistant")

        message.role shouldBe MessageRole.SYSTEM
        message.content shouldBe "you are a helpful assistant"
    }

    @Test
    fun `同一内容のRenderedMessageは構造的に等しい`() {
        val a = RenderedMessage(MessageRole.USER, "hello")
        val b = RenderedMessage(MessageRole.USER, "hello")

        a shouldBe b
    }
}
