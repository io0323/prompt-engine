package promptengine.domain.template.ast

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PromptAstTest {
    @Test
    fun `同一構造のASTノード木は構造的に等価である`() {
        fun tree() =
            BlockNode(
                role = BlockRole.USER,
                body =
                    listOf(
                        TextNode("製品「"),
                        ExprNode(Expression(PropertyRef(listOf("productName")))),
                        TextNode("」について"),
                        IfNode(
                            condition = Expression(PropertyRef(listOf("conversationSummary"))),
                            thenBranch = listOf(TextNode("これまでの経緯: ")),
                            elseBranch = emptyList(),
                        ),
                        EachNode(
                            iterable = Expression(PropertyRef(listOf("examples"))),
                            itemName = "ex",
                            body = listOf(TextNode("例: ")),
                        ),
                    ),
            )

        tree() shouldBe tree()
    }

    @Test
    fun `EachNodeのitemNameが空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            EachNode(
                iterable = Expression(PropertyRef(listOf("examples"))),
                itemName = "",
                body = emptyList(),
            )
        }
    }

    @Test
    fun `IncludeNodeのtargetが空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            IncludeNode(target = "")
        }
    }

    @Test
    fun `MacroCallNodeのnameが空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            MacroCallNode(name = "")
        }
    }

    @Test
    fun `IncludeNodeはversionRangeとbindingsを省略できる`() {
        val node = IncludeNode(target = "fragments/safety-policy")

        node.versionRange shouldBe null
        node.bindings shouldBe emptyMap()
    }
}
