package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PromptMetadataTest {
    @Test
    fun `key name category description tagsを保持する`() {
        val metadata =
            PromptMetadata(
                key = PromptKey("support/faq"),
                name = "FAQ回答生成",
                category = "support",
                description = "顧客対応用",
                tags = listOf("faq", "customer"),
            )

        metadata.name shouldBe "FAQ回答生成"
        metadata.category shouldBe "support"
        metadata.description shouldBe "顧客対応用"
        metadata.tags shouldBe listOf("faq", "customer")
    }

    @Test
    fun `category description tags省略時は既定値になる`() {
        val metadata = PromptMetadata(key = PromptKey("support/faq"), name = "FAQ回答生成")

        metadata.category shouldBe null
        metadata.description shouldBe null
        metadata.tags shouldBe emptyList()
    }

    @Test
    fun `nameが空文字ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { PromptMetadata(key = PromptKey("support/faq"), name = "") }
    }

    @Test
    fun `nameが空白のみならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { PromptMetadata(key = PromptKey("support/faq"), name = "   ") }
    }
}
