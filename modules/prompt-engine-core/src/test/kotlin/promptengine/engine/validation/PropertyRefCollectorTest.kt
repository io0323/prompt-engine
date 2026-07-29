package promptengine.engine.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.FilterCall
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.NumberLiteral
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.StringLiteral
import promptengine.domain.template.ast.TextNode

class PropertyRefCollectorTest {
    private fun ref(vararg path: String) = PropertyRef(path.toList())

    @Test
    fun `TextNodeはPropertyRefに寄与しない`() {
        PropertyRefCollector.collect(listOf(TextNode("hello"))) shouldBe emptyList()
    }

    @Test
    fun `ExprNodeのoperandがPropertyRefならそれを収集する`() {
        val node = ExprNode(Expression(ref("productName")))

        PropertyRefCollector.collect(listOf(node)) shouldBe listOf(ref("productName"))
    }

    @Test
    fun `Expressionのoperandがリテラルなら収集しない`() {
        val node = ExprNode(Expression(StringLiteral("literal")))

        PropertyRefCollector.collect(listOf(node)) shouldBe emptyList()
    }

    @Test
    fun `IfNodeはcondition then elseブランチいずれのPropertyRefも収集する`() {
        val node =
            IfNode(
                condition = Expression(ref("flag")),
                thenBranch = listOf(ExprNode(Expression(ref("thenVar")))),
                elseBranch = listOf(ExprNode(Expression(ref("elseVar")))),
            )

        PropertyRefCollector.collect(listOf(node)) shouldBe listOf(ref("flag"), ref("thenVar"), ref("elseVar"))
    }

    @Test
    fun `EachNodeはiterableとbody双方のPropertyRefを収集する`() {
        val node =
            EachNode(
                iterable = Expression(ref("items")),
                itemName = "item",
                body = listOf(ExprNode(Expression(ref("bodyVar")))),
            )

        PropertyRefCollector.collect(listOf(node)) shouldBe listOf(ref("items"), ref("bodyVar"))
    }

    @Test
    fun `BlockNodeはbody内のPropertyRefを収集する`() {
        val node = BlockNode(BlockRole.SYSTEM, listOf(ExprNode(Expression(ref("bodyVar")))))

        PropertyRefCollector.collect(listOf(node)) shouldBe listOf(ref("bodyVar"))
    }

    @Test
    fun `IncludeNode MacroCallNodeはPropertyRefに寄与せずクラッシュしない`() {
        val nodes = listOf(IncludeNode(target = "safety"), MacroCallNode(name = "bulletList"))

        PropertyRefCollector.collect(nodes) shouldBe emptyList()
    }

    @Test
    fun `フィルタ引数のPropertyRefも収集する`() {
        val expression = Expression(ref("longText"), filters = listOf(FilterCall("truncate", listOf(ref("limitVar")))))

        PropertyRefCollector.collect(listOf(ExprNode(expression))) shouldBe listOf(ref("longText"), ref("limitVar"))
    }

    @Test
    fun `フィルタ引数がリテラルなら収集しない`() {
        val expression =
            Expression(ref("longText"), filters = listOf(FilterCall("truncate", listOf(NumberLiteral(3.0)))))

        PropertyRefCollector.collect(listOf(ExprNode(expression))) shouldBe listOf(ref("longText"))
    }
}
