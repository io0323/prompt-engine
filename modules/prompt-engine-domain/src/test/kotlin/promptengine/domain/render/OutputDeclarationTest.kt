package promptengine.domain.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** [OutputDeclaration]の契約テスト（設計書§15.1 `output:`ブロック、ADR-0015決定9）。 */
class OutputDeclarationTest {
    @Test
    fun `schemaRef省略時はnullになる`() {
        val declaration = OutputDeclaration(format = OutputFormat.JSON)

        declaration.format shouldBe OutputFormat.JSON
        declaration.schemaRef shouldBe null
    }

    @Test
    fun `schemaRefを指定できる`() {
        val declaration = OutputDeclaration(format = OutputFormat.JSON, schemaRef = "schemas/answer@1")

        declaration.schemaRef shouldBe "schemas/answer@1"
    }

    @Test
    fun `同じ内容なら等価 formatが異なれば非等価`() {
        val a = OutputDeclaration(format = OutputFormat.TEXT)
        val b = OutputDeclaration(format = OutputFormat.TEXT)
        val c = OutputDeclaration(format = OutputFormat.JSON)

        a shouldBe b
        a shouldNotBe c
    }
}
