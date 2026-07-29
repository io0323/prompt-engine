package promptengine.engine.compiler

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode

/** [MacrosFieldMapper]のテスト（設計書§15.6、ADR-0010決定5）。 */
class MacrosFieldMapperTest {
    @Test
    fun `nullなら空リストを返す`() {
        MacrosFieldMapper.parse(null).shouldBeEmpty()
    }

    @Test
    fun `nameとparamsとbodyを解決する`() {
        val raw =
            listOf(
                mapOf(
                    "name" to "bulletList",
                    "params" to listOf("items"),
                    "body" to "{{#each items as i}}{{ i }}{{/each}}",
                ),
            )

        val result = MacrosFieldMapper.parse(raw)

        result.single().name shouldBe "bulletList"
        result.single().params shouldBe listOf("items")
        result.single().body shouldBe
            listOf(
                EachNode(
                    Expression(PropertyRef(listOf("items"))),
                    "i",
                    listOf(ExprNode(Expression(PropertyRef(listOf("i"))))),
                ),
            )
    }

    @Test
    fun `プレーンテキストのbodyも解決する`() {
        val raw = listOf(mapOf("name" to "hi", "body" to "hello"))

        val result = MacrosFieldMapper.parse(raw)

        result.single().body shouldBe listOf(TextNode("hello"))
    }

    @Test
    fun `paramsを省略すると空リストになる`() {
        val raw = listOf(mapOf("name" to "greet", "body" to "hello"))

        val result = MacrosFieldMapper.parse(raw)

        result.single().params.shouldBeEmpty()
    }
}
