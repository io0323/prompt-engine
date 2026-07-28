package promptengine.domain.template

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class TemplateContentTest {
    @Test
    fun `source からcontentHashがSHA-256形式で計算される`() {
        val content = TemplateContent("{{#block body}}{{/block}}")

        content.contentHash shouldMatch Regex("^[0-9a-f]{64}$")
    }

    @Test
    fun `同一のsourceからは常に同一のcontentHashが計算される 決定性`() {
        val first = TemplateContent("{{#block body}}{{/block}}")
        val second = TemplateContent("{{#block body}}{{/block}}")

        first.contentHash shouldBe second.contentHash
    }

    @Test
    fun `source が空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { TemplateContent("") }
    }
}
