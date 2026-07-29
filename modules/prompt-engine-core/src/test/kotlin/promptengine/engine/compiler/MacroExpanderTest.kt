package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.MacroNotFoundException
import promptengine.domain.composition.MacroRecursionException
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.StringLiteral
import promptengine.domain.template.ast.TextNode

/** [MacroExpander]のテスト（設計書§15.6、ADR-0010決定5）。 */
class MacroExpanderTest {
    private val expander = MacroExpander()

    @Test
    fun `macro呼出を無しの本文はそのまま返る`() {
        val body = listOf(TextNode("plain"))

        expander.expand(body, emptyList()) shouldBe body
    }

    @Test
    fun `macroを引数で置換して展開する`() {
        val declaration =
            MacroDeclaration("greet", listOf("name"), listOf(ExprNode(Expression(PropertyRef(listOf("name"))))))

        val result =
            expander.expand(
                listOf(MacroCallNode("greet", mapOf("name" to Expression(StringLiteral("Alice"))))),
                listOf(declaration),
            )

        result shouldBe listOf(ExprNode(Expression(StringLiteral("Alice"))))
    }

    @Test
    fun `宣言単位に定義の無いmacro呼出はMacroNotFoundExceptionを投げる`() {
        val exception =
            shouldThrow<MacroNotFoundException> {
                expander.expand(listOf(MacroCallNode("unknown")), emptyList())
            }
        exception.macroName shouldBe "unknown"
    }

    @Test
    fun `superという名前の呼出も宣言が無ければMacroNotFoundExceptionを投げる`() {
        shouldThrow<MacroNotFoundException> {
            expander.expand(listOf(MacroCallNode("super")), emptyList())
        }
    }

    @Test
    fun `自己再帰呼出はMacroRecursionExceptionを投げる`() {
        val declaration = MacroDeclaration("loop", emptyList(), listOf(MacroCallNode("loop")))

        val exception =
            shouldThrow<MacroRecursionException> {
                expander.expand(listOf(MacroCallNode("loop")), listOf(declaration))
            }
        exception.macroName shouldBe "loop"
    }

    @Test
    fun `間接的な再帰呼出もMacroRecursionExceptionを投げる`() {
        val a = MacroDeclaration("a", emptyList(), listOf(MacroCallNode("b")))
        val b = MacroDeclaration("b", emptyList(), listOf(MacroCallNode("a")))

        shouldThrow<MacroRecursionException> {
            expander.expand(listOf(MacroCallNode("a")), listOf(a, b))
        }
    }

    @Test
    fun `同一macroを兄弟ノードで複数回呼ぶのは再帰ではない`() {
        val declaration = MacroDeclaration("greet", emptyList(), listOf(TextNode("hi")))

        val result = expander.expand(listOf(MacroCallNode("greet"), MacroCallNode("greet")), listOf(declaration))

        result shouldBe listOf(TextNode("hi"), TextNode("hi"))
    }
}
