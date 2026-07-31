package promptengine.engine.formatter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.render.OutputFormat

class TextOutputFormatterTest {
    private val formatter = TextOutputFormatter()

    @Test
    fun `formatはTEXT`() {
        formatter.format() shouldBe OutputFormat.TEXT
    }

    @Test
    fun `instructionは常に空文字`() {
        formatter.instruction(null) shouldBe ""
        formatter.instruction(OutputSchema(id = "unused")) shouldBe ""
    }

    @Test
    fun `parseは常に成功しrawをそのまま保持する`() {
        val result = formatter.parse("hello world", null)

        result.format shouldBe OutputFormat.TEXT
        result.raw shouldBe "hello world"
        result.fields shouldBe emptyMap()
    }
}
