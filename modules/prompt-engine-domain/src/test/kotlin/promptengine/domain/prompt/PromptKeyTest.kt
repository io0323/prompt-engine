package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PromptKeyTest {
    @Test
    fun `namespace slash name 形式の文字列でPromptKeyを生成できる`() {
        PromptKey("support/faq-answer").value shouldBe "support/faq-answer"
    }

    @Test
    fun `namespace と name をそれぞれ取得できる`() {
        val key = PromptKey("support/faq-answer")

        key.namespace shouldBe "support"
        key.name shouldBe "faq-answer"
    }

    @Test
    fun `スラッシュを含まない文字列だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { PromptKey("faqanswer") }
    }

    @Test
    fun `大文字を含む文字列だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { PromptKey("Support/FaqAnswer") }
    }

    @Test
    fun `不正な記号を含む文字列だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { PromptKey("support/faq_answer!") }
    }
}
