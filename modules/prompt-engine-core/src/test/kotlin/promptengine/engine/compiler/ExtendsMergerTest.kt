package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.DuplicateSuperCallException
import promptengine.domain.composition.SuperWithoutParentBlockException
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.BooleanLiteral
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.NumberLiteral
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode

/**
 * [ExtendsMerger]のテスト（設計書§15.3、ADR-0010決定3・6）。
 */
class ExtendsMergerTest {
    private val merger = ExtendsMerger()

    private fun superCall(): MacroCallNode = MacroCallNode("super")

    @Test
    fun `親を持たないリーフのみの場合は自身の本文をそのまま返す`() {
        val leaf = listOf(block(BlockRole.USER, "leaf user"))

        merger.merge(emptyList(), leaf) shouldBe leaf
    }

    @Test
    fun `子の同名blockが親を単純に上書きする`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent system"))
        val leaf = listOf(block(BlockRole.SYSTEM, "leaf system"))

        val result = merger.merge(listOf(parent), leaf)

        result shouldBe listOf(block(BlockRole.SYSTEM, "leaf system"))
    }

    @Test
    fun `子が上書きしないblockは親の内容をそのまま継承する`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent system"), block(BlockRole.USER, "parent user"))
        val leaf = listOf(block(BlockRole.USER, "leaf user"))

        val result = merger.merge(listOf(parent), leaf)

        result shouldBe listOf(block(BlockRole.USER, "leaf user"), block(BlockRole.SYSTEM, "parent system"))
    }

    @Test
    fun `super で親block内容を子block内に挿入する`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent content"))
        val leaf = listOf(BlockNode(BlockRole.SYSTEM, listOf(TextNode("before "), superCall(), TextNode(" after"))))

        val result = merger.merge(listOf(parent), leaf)

        result shouldBe
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(TextNode("before "), TextNode("parent content"), TextNode(" after")),
                ),
            )
    }

    @Test
    fun `多段継承では super は直近の親が確定した内容を指す`() {
        val grandparent = listOf(block(BlockRole.SYSTEM, "grandparent"))
        val parent = listOf(BlockNode(BlockRole.SYSTEM, listOf(TextNode("parent-"), superCall())))
        val leaf = listOf(BlockNode(BlockRole.SYSTEM, listOf(TextNode("leaf-"), superCall())))

        // ReferenceResolver.resolveExtendsChainと同じ順序（直近の親→根本）で渡す。
        val result = merger.merge(listOf(parent, grandparent), leaf)

        result shouldBe
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(TextNode("leaf-"), TextNode("parent-"), TextNode("grandparent")),
                ),
            )
    }

    @Test
    fun `親に無いblockを子が新たに定義できる`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent system"))
        val leaf = listOf(block(BlockRole.SYSTEM, "leaf system"), block(BlockRole.ASSISTANT, "leaf assistant"))

        val result = merger.merge(listOf(parent), leaf)

        result shouldBe listOf(block(BlockRole.SYSTEM, "leaf system"), block(BlockRole.ASSISTANT, "leaf assistant"))
    }

    @Test
    fun `根本のTemplate自身が super を呼ぶとSuperWithoutParentBlockExceptionを投げる`() {
        val root = listOf(BlockNode(BlockRole.SYSTEM, listOf(superCall())))

        shouldThrow<SuperWithoutParentBlockException> {
            merger.merge(emptyList(), root)
        }.role shouldBe BlockRole.SYSTEM
    }

    @Test
    fun `親に存在しないroleのblockで super を呼ぶとSuperWithoutParentBlockExceptionを投げる`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent system"))
        val leaf = listOf(BlockNode(BlockRole.ASSISTANT, listOf(superCall())))

        shouldThrow<SuperWithoutParentBlockException> {
            merger.merge(listOf(parent), leaf)
        }.role shouldBe BlockRole.ASSISTANT
    }

    @Test
    fun `同一block内で super を複数回呼ぶとDuplicateSuperCallExceptionを投げる`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent system"))
        val leaf = listOf(BlockNode(BlockRole.SYSTEM, listOf(superCall(), superCall())))

        shouldThrow<DuplicateSuperCallException> {
            merger.merge(listOf(parent), leaf)
        }.role shouldBe BlockRole.SYSTEM
    }

    @Test
    fun `super は if の thenBranch elseBranch 内にネストしても検出・置換される`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent"))
        val condition = Expression(BooleanLiteral(true))
        val leaf =
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(IfNode(condition, thenBranch = listOf(superCall()), elseBranch = listOf(TextNode("else")))),
                ),
            )

        val result = merger.merge(listOf(parent), leaf)

        result shouldBe
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(
                        IfNode(
                            condition,
                            thenBranch = listOf(TextNode("parent")),
                            elseBranch = listOf(TextNode("else")),
                        ),
                    ),
                ),
            )
    }

    @Test
    fun `super は each の body 内にネストしても検出・置換される`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent"))
        val iterable = Expression(PropertyRef(listOf("items")))
        val leaf = listOf(BlockNode(BlockRole.SYSTEM, listOf(EachNode(iterable, "i", listOf(superCall())))))

        val result = merger.merge(listOf(parent), leaf)

        result shouldBe
            listOf(BlockNode(BlockRole.SYSTEM, listOf(EachNode(iterable, "i", listOf(TextNode("parent"))))))
    }

    @Test
    fun `if の thenBranch と elseBranch に分かれた super も複数回呼出として検出する`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent"))
        val condition = Expression(BooleanLiteral(true))
        val leaf =
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(IfNode(condition, thenBranch = listOf(superCall()), elseBranch = listOf(superCall()))),
                ),
            )

        shouldThrow<DuplicateSuperCallException> {
            merger.merge(listOf(parent), leaf)
        }.role shouldBe BlockRole.SYSTEM
    }

    @Test
    fun `引数付きの super はsuper挿入として扱わずそのまま残る`() {
        val parent = listOf(block(BlockRole.SYSTEM, "parent"))
        val argedSuper = MacroCallNode("super", mapOf("x" to Expression(NumberLiteral(1.0))))
        val leaf = listOf(BlockNode(BlockRole.SYSTEM, listOf(argedSuper)))

        val result = merger.merge(listOf(parent), leaf)

        result shouldBe listOf(BlockNode(BlockRole.SYSTEM, listOf(argedSuper)))
    }

    @Test
    fun `block外のトップレベルノードは祖先からマージされずリーフ自身のものだけが残る`() {
        val parent = listOf(TextNode("parent stray text"), block(BlockRole.SYSTEM, "parent system"))
        val leaf = listOf(TextNode("leaf stray text"), block(BlockRole.SYSTEM, "leaf system"))

        val result = merger.merge(listOf(parent), leaf)

        result shouldBe listOf(TextNode("leaf stray text"), block(BlockRole.SYSTEM, "leaf system"))
    }

    private fun block(
        role: BlockRole,
        text: String,
    ): PromptAst = BlockNode(role, listOf(TextNode(text)))
}
