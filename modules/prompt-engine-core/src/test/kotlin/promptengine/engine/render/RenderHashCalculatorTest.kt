package promptengine.engine.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage

class RenderHashCalculatorTest {
    private val messages = listOf(RenderedMessage(MessageRole.SYSTEM, "hi"))

    @Test
    fun `同一入力からは常に同一のhashを返す`() {
        val hashA = RenderHashCalculator.compute(messages, OutputFormat.TEXT, "pe-tmpl/1")
        val hashB = RenderHashCalculator.compute(messages, OutputFormat.TEXT, "pe-tmpl/1")

        hashA shouldBe hashB
    }

    @Test
    fun `engineIdが異なればhashも異なる`() {
        val hashA = RenderHashCalculator.compute(messages, OutputFormat.TEXT, "pe-tmpl/1")
        val hashB = RenderHashCalculator.compute(messages, OutputFormat.TEXT, "pe-repair/1")

        hashA shouldNotBe hashB
    }

    @Test
    fun `engineVersionが異なればhashも異なる`() {
        val hashA = RenderHashCalculator.compute(messages, OutputFormat.TEXT, "pe-tmpl/1", engineVersion = "1")
        val hashB = RenderHashCalculator.compute(messages, OutputFormat.TEXT, "pe-tmpl/1", engineVersion = "2")

        hashA shouldNotBe hashB
    }
}
