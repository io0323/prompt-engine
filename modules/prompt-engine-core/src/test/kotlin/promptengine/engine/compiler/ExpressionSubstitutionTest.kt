package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.InvalidVariableSubstitutionException
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.FilterCall
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.StringLiteral
import promptengine.domain.template.ast.TextNode

/** [ExpressionSubstitution]のテスト（設計書§15.5/§15.6、ADR-0010決定2）。 */
class ExpressionSubstitutionTest {
    @Test
    fun `束縛対象外の変数はそのまま残る`() {
        val nodes = listOf(ExprNode(Expression(PropertyRef(listOf("other")))))

        val result = ExpressionSubstitution.substitute(nodes, mapOf("k" to Expression(StringLiteral("v"))))

        result shouldBe nodes
    }

    @Test
    fun `束縛値がリテラルなら丸ごと置換する`() {
        val nodes = listOf(ExprNode(Expression(PropertyRef(listOf("k")))))

        val result = ExpressionSubstitution.substitute(nodes, mapOf("k" to Expression(StringLiteral("v"))))

        result shouldBe listOf(ExprNode(Expression(StringLiteral("v"))))
    }

    @Test
    fun `束縛値がPropertyRefならパスの先頭だけ差し替え残りのパスを保持する`() {
        val nodes = listOf(ExprNode(Expression(PropertyRef(listOf("k", "field")))))

        val result =
            ExpressionSubstitution.substitute(nodes, mapOf("k" to Expression(PropertyRef(listOf("outer", "user")))))

        result shouldBe listOf(ExprNode(Expression(PropertyRef(listOf("outer", "user", "field")))))
    }

    @Test
    fun `束縛値がリテラルなのにさらにドット参照すると例外を投げる`() {
        val nodes = listOf(ExprNode(Expression(PropertyRef(listOf("k", "field")))))

        shouldThrow<InvalidVariableSubstitutionException> {
            ExpressionSubstitution.substitute(nodes, mapOf("k" to Expression(StringLiteral("v"))))
        }
    }

    @Test
    fun `フィルタは束縛側から元側の順に連結する`() {
        val nodes = listOf(ExprNode(Expression(PropertyRef(listOf("k")), filters = listOf(FilterCall("upper")))))
        val bound = Expression(StringLiteral("v"), filters = listOf(FilterCall("trim")))

        val result = ExpressionSubstitution.substitute(nodes, mapOf("k" to bound))

        result shouldBe
            listOf(ExprNode(Expression(StringLiteral("v"), filters = listOf(FilterCall("trim"), FilterCall("upper")))))
    }

    @Test
    fun `if each block include macro を再帰的に置換する`() {
        val nodes =
            listOf(
                IfNode(
                    condition = Expression(PropertyRef(listOf("k"))),
                    thenBranch = listOf(ExprNode(Expression(PropertyRef(listOf("k"))))),
                ),
            )

        val result = ExpressionSubstitution.substitute(nodes, mapOf("k" to Expression(StringLiteral("v"))))

        result shouldBe
            listOf(
                IfNode(
                    condition = Expression(StringLiteral("v")),
                    thenBranch = listOf(ExprNode(Expression(StringLiteral("v")))),
                ),
            )
    }

    @Test
    fun `each のループ変数名は束縛をシャドーイングする`() {
        val nodes =
            listOf(
                EachNode(
                    iterable = Expression(PropertyRef(listOf("k"))),
                    itemName = "k",
                    body = listOf(ExprNode(Expression(PropertyRef(listOf("k"))))),
                ),
            )

        val result = ExpressionSubstitution.substitute(nodes, mapOf("k" to Expression(StringLiteral("v"))))

        result shouldBe
            listOf(
                EachNode(
                    iterable = Expression(StringLiteral("v")),
                    itemName = "k",
                    body = listOf(ExprNode(Expression(PropertyRef(listOf("k"))))),
                ),
            )
    }

    @Test
    fun `テキストノードは変化しない`() {
        val nodes = listOf(TextNode("plain"))

        ExpressionSubstitution.substitute(nodes, mapOf("k" to Expression(StringLiteral("v")))) shouldBe nodes
    }
}
