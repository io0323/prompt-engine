package promptengine.engine.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.context.ContextBindingSet
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
import promptengine.domain.variable.BindingSet

class AstTextEstimatorTest {
    @Test
    fun `TextNodeはそのまま連結する`() {
        val text =
            AstTextEstimator.estimate(
                listOf(TextNode("hello "), TextNode("world")),
                BindingSet.empty(),
                ContextBindingSet.empty(),
            )

        text shouldBe "hello world"
    }

    @Test
    fun `変数プレースホルダは束縛値の文字列化を連結する`() {
        val nodes = listOf(ExprNode(Expression(PropertyRef(listOf("productName")))))
        val bindings = BindingSet(mapOf("productName" to "widget"))

        AstTextEstimator.estimate(nodes, bindings, ContextBindingSet.empty()) shouldBe "widget"
    }

    @Test
    fun `未束縛の変数プレースホルダは空文字として扱う`() {
        val nodes = listOf(TextNode("a"), ExprNode(Expression(PropertyRef(listOf("missing")))), TextNode("b"))

        AstTextEstimator.estimate(nodes, BindingSet.empty(), ContextBindingSet.empty()) shouldBe "ab"
    }

    @Test
    fun `context参照はContextBindingSetからscope dot pathで引く`() {
        val nodes = listOf(ExprNode(Expression(PropertyRef(listOf("context", "user", "displayName")))))
        val contextBindings = ContextBindingSet(mapOf("user.displayName" to "Alice"))

        AstTextEstimator.estimate(nodes, BindingSet.empty(), contextBindings) shouldBe "Alice"
    }

    @Test
    fun `truncateフィルタは結果をその長さで切り詰める`() {
        val expression =
            Expression(
                PropertyRef(listOf("longText")),
                filters = listOf(FilterCall("truncate", listOf(NumberLiteral(3.0)))),
            )
        val bindings = BindingSet(mapOf("longText" to "abcdefgh"))

        AstTextEstimator.estimate(listOf(ExprNode(expression)), bindings, ContextBindingSet.empty()) shouldBe "abc"
    }

    @Test
    fun `IfNodeはthen elseのうち長い方を採用する`() {
        val node =
            IfNode(
                condition = Expression(PropertyRef(listOf("flag"))),
                thenBranch = listOf(TextNode("short")),
                elseBranch = listOf(TextNode("a much longer branch")),
            )

        val text = AstTextEstimator.estimate(listOf(node), BindingSet.empty(), ContextBindingSet.empty())

        text shouldBe "a much longer branch"
    }

    @Test
    fun `EachNodeは実配列の要素数ぶんbodyを繰り返す`() {
        val node =
            EachNode(
                iterable = Expression(PropertyRef(listOf("items"))),
                itemName = "item",
                body = listOf(TextNode("x")),
            )
        val bindings = BindingSet(mapOf("items" to listOf(1, 2, 3)))

        AstTextEstimator.estimate(listOf(node), bindings, ContextBindingSet.empty()) shouldBe "xxx"
    }

    @Test
    fun `EachNodeのiterableが解決できなければ1回分として扱う`() {
        val node =
            EachNode(
                iterable = Expression(PropertyRef(listOf("items"))),
                itemName = "item",
                body = listOf(TextNode("x")),
            )

        AstTextEstimator.estimate(listOf(node), BindingSet.empty(), ContextBindingSet.empty()) shouldBe "x"
    }

    @Test
    fun `BlockNodeはroleを問わずbodyをそのまま連結する`() {
        val node = BlockNode(BlockRole.USER, listOf(TextNode("a"), TextNode("b")))

        AstTextEstimator.estimate(listOf(node), BindingSet.empty(), ContextBindingSet.empty()) shouldBe "ab"
    }

    @Test
    fun `IncludeNode MacroCallNodeは空文字として扱いクラッシュしない`() {
        val nodes =
            listOf(TextNode("a"), IncludeNode(target = "safety"), MacroCallNode(name = "bulletList"), TextNode("b"))

        AstTextEstimator.estimate(nodes, BindingSet.empty(), ContextBindingSet.empty()) shouldBe "ab"
    }

    @Test
    fun `IfNodeでthen elseが同じ長さならthenを採用する`() {
        val node =
            IfNode(
                condition = Expression(PropertyRef(listOf("flag"))),
                thenBranch = listOf(TextNode("abc")),
                elseBranch = listOf(TextNode("xyz")),
            )

        AstTextEstimator.estimate(listOf(node), BindingSet.empty(), ContextBindingSet.empty()) shouldBe "abc"
    }

    @Test
    fun `operandがリテラルなら空文字として扱う`() {
        val nodes = listOf(ExprNode(Expression(StringLiteral("literal"))))

        AstTextEstimator.estimate(nodes, BindingSet.empty(), ContextBindingSet.empty()) shouldBe ""
    }

    @Test
    fun `truncate以外のフィルタは文字数に影響しない`() {
        val expression = Expression(PropertyRef(listOf("longText")), filters = listOf(FilterCall("upper")))
        val bindings = BindingSet(mapOf("longText" to "abcdefgh"))

        AstTextEstimator.estimate(listOf(ExprNode(expression)), bindings, ContextBindingSet.empty()) shouldBe "abcdefgh"
    }

    @Test
    fun `truncateフィルタに引数が無ければ切り詰めない`() {
        val expression = Expression(PropertyRef(listOf("longText")), filters = listOf(FilterCall("truncate")))
        val bindings = BindingSet(mapOf("longText" to "abcdefgh"))

        AstTextEstimator.estimate(listOf(ExprNode(expression)), bindings, ContextBindingSet.empty()) shouldBe "abcdefgh"
    }

    @Test
    fun `truncateのlimitが文字列長以上なら切り詰めない`() {
        val expression =
            Expression(
                PropertyRef(listOf("longText")),
                filters = listOf(FilterCall("truncate", listOf(NumberLiteral(100.0)))),
            )
        val bindings = BindingSet(mapOf("longText" to "abcdefgh"))

        AstTextEstimator.estimate(listOf(ExprNode(expression)), bindings, ContextBindingSet.empty()) shouldBe "abcdefgh"
    }

    @Test
    fun `truncateのlimitが負なら切り詰めない`() {
        val expression =
            Expression(
                PropertyRef(listOf("longText")),
                filters = listOf(FilterCall("truncate", listOf(NumberLiteral(-1.0)))),
            )
        val bindings = BindingSet(mapOf("longText" to "abcdefgh"))

        AstTextEstimator.estimate(listOf(ExprNode(expression)), bindings, ContextBindingSet.empty()) shouldBe "abcdefgh"
    }

    @Test
    fun `context参照がscopeを持たない短いpathなら空文字として扱う`() {
        val nodes = listOf(ExprNode(Expression(PropertyRef(listOf("context", "user")))))

        AstTextEstimator.estimate(nodes, BindingSet.empty(), ContextBindingSet.empty()) shouldBe ""
    }
}
