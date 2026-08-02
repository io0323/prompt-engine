package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.render.OutputFormat

/** [OutputFieldMapper]の単体テスト（[ValidationFieldMapperTest]と同型、CodeRabbitレビュー指摘対応）。 */
class OutputFieldMapperTest {
    @Test
    fun `nullならoutput ブロック未宣言としてnullを返す`() {
        OutputFieldMapper.parse(null) shouldBe null
    }

    @Test
    fun `formatとschemaRefをOutputDeclarationへ変換する`() {
        val raw = mapOf("format" to "json", "schemaRef" to "schemas/answer@1")

        OutputFieldMapper.parse(raw) shouldBe OutputDeclaration(OutputFormat.JSON, "schemas/answer@1")
    }

    @Test
    fun `schemaRef省略時はnullになる`() {
        OutputFieldMapper.parse(mapOf("format" to "text")) shouldBe OutputDeclaration(OutputFormat.TEXT, null)
    }

    @Test
    fun `format xml markdown text をそれぞれ変換する`() {
        OutputFieldMapper.parse(mapOf("format" to "xml"))!!.format shouldBe OutputFormat.XML
        OutputFieldMapper.parse(mapOf("format" to "markdown"))!!.format shouldBe OutputFormat.MARKDOWN
        OutputFieldMapper.parse(mapOf("format" to "text"))!!.format shouldBe OutputFormat.TEXT
    }

    @Test
    fun `マッピングでなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { OutputFieldMapper.parse("not-a-map") }
    }

    @Test
    fun `formatが欠落していればIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { OutputFieldMapper.parse(mapOf("schemaRef" to "schemas/answer@1")) }
    }

    @Test
    fun `formatが文字列でなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { OutputFieldMapper.parse(mapOf("format" to 1)) }
    }

    @Test
    fun `formatがjson xml markdown text以外ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { OutputFieldMapper.parse(mapOf("format" to "yaml")) }
    }

    @Test
    fun `schemaRefが文字列でなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            OutputFieldMapper.parse(mapOf("format" to "json", "schemaRef" to 123))
        }
    }
}
