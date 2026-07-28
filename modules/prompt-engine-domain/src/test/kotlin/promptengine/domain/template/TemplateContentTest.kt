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
    fun `既知のsourceからはSHA-256の既知の値が計算される`() {
        TemplateContent("abc").contentHash shouldBe
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
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

    @Test
    fun `source が空白のみだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { TemplateContent(" \t\n") }
    }
}
