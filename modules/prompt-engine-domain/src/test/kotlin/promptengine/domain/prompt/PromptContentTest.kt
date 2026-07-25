package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class PromptContentTest {
    @Test
    fun `source からcontentHashがSHA-256形式で計算される`() {
        val content = PromptContent("Answer: {{question}}")

        content.contentHash shouldMatch Regex("^[0-9a-f]{64}$")
    }

    @Test
    fun `同一のsourceからは常に同一のcontentHashが計算される 決定性`() {
        val first = PromptContent("Answer: {{question}}")
        val second = PromptContent("Answer: {{question}}")

        first.contentHash shouldBe second.contentHash
    }

    @Test
    fun `異なるsourceからは異なるcontentHashが計算される`() {
        val first = PromptContent("Answer: {{question}}")
        val second = PromptContent("Answer: {{other}}")

        (first.contentHash == second.contentHash) shouldBe false
    }

    @Test
    fun `source が空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { PromptContent("") }
    }
}
