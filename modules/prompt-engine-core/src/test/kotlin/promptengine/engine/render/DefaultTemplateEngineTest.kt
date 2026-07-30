package promptengine.engine.render

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.render.MessageRole
import promptengine.domain.render.RenderedMessage
import promptengine.domain.shared.SensitiveValue
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
import java.util.Locale

class DefaultTemplateEngineTest {
    private val engine = DefaultTemplateEngine()

    @Test
    fun `idはpe-tmpl-1`() {
        engine.id() shouldBe "pe-tmpl/1"
    }

    @Test
    fun `Blockが1つも無ければ本文全体を単一のUSER messageとして扱う`() {
        val result = engine.expand(listOf(TextNode("hello")), BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "hello"))
    }

    @Test
    fun `本文が完全に空でも単一のUSER messageを返す`() {
        val result = engine.expand(emptyList(), BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, ""))
    }

    @Test
    fun `同一roleの複数Blockは出現順のまま個別messageとして保持する`() {
        val body =
            listOf(
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("first"))),
                BlockNode(BlockRole.USER, listOf(TextNode("mid"))),
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("second"))),
            )

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe
            listOf(
                RenderedMessage(MessageRole.SYSTEM, "first"),
                RenderedMessage(MessageRole.USER, "mid"),
                RenderedMessage(MessageRole.SYSTEM, "second"),
            )
    }

    @Test
    fun `Block前後の裸のテキストは暗黙のUSER messageとして分離する`() {
        val body =
            listOf(
                TextNode("intro"),
                BlockNode(BlockRole.SYSTEM, listOf(TextNode("sys"))),
                TextNode("outro"),
            )

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe
            listOf(
                RenderedMessage(MessageRole.USER, "intro"),
                RenderedMessage(MessageRole.SYSTEM, "sys"),
                RenderedMessage(MessageRole.USER, "outro"),
            )
    }

    @Test
    fun `PropertyRefを変数束縛から解決する`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("name")))))
        val bindings = BindingSet(mapOf("name" to "Alice"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "Alice"))
    }

    @Test
    fun `PropertyRefをContextBindingSetから解決する`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("context", "user", "displayName")))))
        val contextBindings = ContextBindingSet(mapOf("user.displayName" to "Bob"))

        val result = engine.expand(body, BindingSet.empty(), contextBindings)

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "Bob"))
    }

    @Test
    fun `未解決のPropertyRefは空文字になる`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("missing")))))

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, ""))
    }

    @Test
    fun `upperフィルタはLocaleに依存せず動作する`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val body = listOf(ExprNode(Expression(PropertyRef(listOf("name")), filters = listOf(FilterCall("upper")))))
            val bindings = BindingSet(mapOf("name" to "istanbul"))

            val result = engine.expand(body, bindings, ContextBindingSet.empty())

            result shouldBe listOf(RenderedMessage(MessageRole.USER, "ISTANBUL"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `truncateフィルタは指定文字数で切り詰める`() {
        val body =
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("name")),
                        filters = listOf(FilterCall("truncate", listOf(NumberLiteral(3.0)))),
                    ),
                ),
            )
        val bindings = BindingSet(mapOf("name" to "hello"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "hel"))
    }

    @Test
    fun `truncateフィルタは引数が無ければ何もしない`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("name")), filters = listOf(FilterCall("truncate")))))
        val bindings = BindingSet(mapOf("name" to "hello"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "hello"))
    }

    @Test
    fun `truncateフィルタは数値以外の引数なら何もしない`() {
        val body =
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("name")),
                        filters = listOf(FilterCall("truncate", listOf(StringLiteral("not-a-number")))),
                    ),
                ),
            )
        val bindings = BindingSet(mapOf("name" to "hello"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "hello"))
    }

    @Test
    fun `truncateフィルタは文字列長以上の指定なら何もしない`() {
        val body =
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("name")),
                        filters = listOf(FilterCall("truncate", listOf(NumberLiteral(100.0)))),
                    ),
                ),
            )
        val bindings = BindingSet(mapOf("name" to "hello"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "hello"))
    }

    @Test
    fun `truncateフィルタは負の指定なら何もしない`() {
        val body =
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("name")),
                        filters = listOf(FilterCall("truncate", listOf(NumberLiteral(-1.0)))),
                    ),
                ),
            )
        val bindings = BindingSet(mapOf("name" to "hello"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "hello"))
    }

    @Test
    fun `truncateフィルタは未解決値には適用されず空文字のままになる`() {
        val body =
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("missing")),
                        filters = listOf(FilterCall("truncate", listOf(NumberLiteral(3.0)))),
                    ),
                ),
            )

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, ""))
    }

    @Test
    fun `NaNのDoubleは決定的にNaNと文字列化される`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("count")))))
        val bindings = BindingSet(mapOf("count" to Double.NaN))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "NaN"))
    }

    @Test
    fun `defaultフィルタは値が未解決の場合のみ適用される`() {
        val body =
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("missing")),
                        filters = listOf(FilterCall("default", listOf(StringLiteral("N/A")))),
                    ),
                ),
                ExprNode(
                    Expression(
                        PropertyRef(listOf("present")),
                        filters = listOf(FilterCall("default", listOf(StringLiteral("N/A")))),
                    ),
                ),
            )
        val bindings = BindingSet(mapOf("present" to "value"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "N/Avalue"))
    }

    @Test
    fun `default引数を指定しないフィルタは未解決値を空文字にする`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("missing")), filters = listOf(FilterCall("default")))))

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, ""))
    }

    @Test
    fun `整数値のDoubleは小数点無しで文字列化する`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("count")))))
        val bindings = BindingSet(mapOf("count" to 3.0))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "3"))
    }

    @Test
    fun `IfNodeは条件がtrueならthenBranchを描画する`() {
        val body =
            listOf(
                IfNode(
                    condition = Expression(PropertyRef(listOf("flag"))),
                    thenBranch = listOf(TextNode("yes")),
                    elseBranch = listOf(TextNode("no")),
                ),
            )
        val bindings = BindingSet(mapOf("flag" to true))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "yes"))
    }

    @Test
    fun `EachNodeはList要素ごとにitemNameを束縛して繰り返す`() {
        val body =
            listOf(
                EachNode(
                    iterable = Expression(PropertyRef(listOf("items"))),
                    itemName = "item",
                    body = listOf(ExprNode(Expression(PropertyRef(listOf("item"))))),
                ),
            )
        val bindings = BindingSet(mapOf("items" to listOf("a", "b", "c")))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "abc"))
    }

    @Test
    fun `EachNodeのitemNameは外側の束縛をシャドーイングする`() {
        val body =
            listOf(
                EachNode(
                    iterable = Expression(PropertyRef(listOf("item"))),
                    itemName = "item",
                    body = listOf(ExprNode(Expression(PropertyRef(listOf("item"))))),
                ),
            )
        val bindings = BindingSet(mapOf("item" to listOf("x", "y")))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "xy"))
    }

    @Test
    fun `トップレベルのEachNodeで反復対象が解決できなければ1回分として扱う`() {
        val body =
            listOf(
                EachNode(
                    iterable = Expression(PropertyRef(listOf("missing"))),
                    itemName = "item",
                    body = listOf(TextNode("once")),
                ),
            )

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "once"))
    }

    @Test
    fun `contextの後にscope名しか無い短いPropertyRefは未解決として空文字になる`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("context", "user")))))
        val contextBindings = ContextBindingSet(mapOf("user.name" to "Alice"))

        val result = engine.expand(body, BindingSet.empty(), contextBindings)

        result shouldBe listOf(RenderedMessage(MessageRole.USER, ""))
    }

    @Test
    fun `トップレベルのEachNodeでnull要素はUnitとして束縛される`() {
        val body =
            listOf(
                EachNode(
                    iterable = Expression(PropertyRef(listOf("items"))),
                    itemName = "item",
                    body = listOf(ExprNode(Expression(PropertyRef(listOf("item"))))),
                ),
            )
        val bindings = BindingSet(mapOf("items" to listOf("a", null)))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "akotlin.Unit"))
    }

    @Test
    fun `Block本文の入れ子EachNodeでnull要素はUnitとして束縛される`() {
        val body =
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(
                        EachNode(
                            iterable = Expression(PropertyRef(listOf("items"))),
                            itemName = "item",
                            body = listOf(ExprNode(Expression(PropertyRef(listOf("item"))))),
                        ),
                    ),
                ),
            )
        val bindings = BindingSet(mapOf("items" to listOf("a", null)))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.SYSTEM, "akotlin.Unit"))
    }

    @Test
    fun `sensitive値は生値がcontentに含まれる`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("apiKey")))))
        val bindings = BindingSet(mapOf("apiKey" to SensitiveValue.of("sk-real-secret")))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "sk-real-secret"))
    }

    @Test
    fun `lowerフィルタは小文字化する`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("name")), filters = listOf(FilterCall("lower")))))
        val bindings = BindingSet(mapOf("name" to "HELLO"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "hello"))
    }

    @Test
    fun `trimフィルタは前後の空白を除去する`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("name")), filters = listOf(FilterCall("trim")))))
        val bindings = BindingSet(mapOf("name" to "  hi  "))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "hi"))
    }

    @Test
    fun `IfNodeの条件は空文字列を偽と判定する`() {
        val body =
            listOf(
                IfNode(
                    condition = Expression(PropertyRef(listOf("flag"))),
                    thenBranch = listOf(TextNode("yes")),
                    elseBranch = listOf(TextNode("no")),
                ),
            )

        val emptyResult = engine.expand(body, BindingSet(mapOf("flag" to "")), ContextBindingSet.empty())
        val nonEmptyResult = engine.expand(body, BindingSet(mapOf("flag" to "x")), ContextBindingSet.empty())

        emptyResult shouldBe listOf(RenderedMessage(MessageRole.USER, "no"))
        nonEmptyResult shouldBe listOf(RenderedMessage(MessageRole.USER, "yes"))
    }

    @Test
    fun `IfNodeの条件は0を偽と判定する`() {
        val body =
            listOf(
                IfNode(
                    condition = Expression(PropertyRef(listOf("flag"))),
                    thenBranch = listOf(TextNode("yes")),
                    elseBranch = listOf(TextNode("no")),
                ),
            )

        val zeroResult = engine.expand(body, BindingSet(mapOf("flag" to 0.0)), ContextBindingSet.empty())
        val nonZeroResult = engine.expand(body, BindingSet(mapOf("flag" to 1.0)), ContextBindingSet.empty())

        zeroResult shouldBe listOf(RenderedMessage(MessageRole.USER, "no"))
        nonZeroResult shouldBe listOf(RenderedMessage(MessageRole.USER, "yes"))
    }

    @Test
    fun `複数segmentのPropertyRefはMap値を辿って解決する`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("user", "name")))))
        val bindings = BindingSet(mapOf("user" to mapOf("name" to "Alice")))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "Alice"))
    }

    @Test
    fun `Map以外の値への複数segmentPropertyRefは未解決として空文字になる`() {
        val body = listOf(ExprNode(Expression(PropertyRef(listOf("name", "field")))))
        val bindings = BindingSet(mapOf("name" to "not-a-map"))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, ""))
    }

    @Test
    fun `Block本文に入れ子のIfNodeがあれば選択された枝を描画する`() {
        val body =
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(
                        IfNode(
                            condition = Expression(PropertyRef(listOf("flag"))),
                            thenBranch = listOf(TextNode("yes")),
                            elseBranch = listOf(TextNode("no")),
                        ),
                    ),
                ),
            )
        val bindings = BindingSet(mapOf("flag" to true))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.SYSTEM, "yes"))
    }

    @Test
    fun `Block本文に入れ子のEachNodeがあれば要素ごとに繰り返す`() {
        val body =
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(
                        EachNode(
                            iterable = Expression(PropertyRef(listOf("items"))),
                            itemName = "item",
                            body = listOf(ExprNode(Expression(PropertyRef(listOf("item"))))),
                        ),
                    ),
                ),
            )
        val bindings = BindingSet(mapOf("items" to listOf("a", "b")))

        val result = engine.expand(body, bindings, ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.SYSTEM, "ab"))
    }

    @Test
    fun `Block本文に入れ子のEachNodeで反復対象が解決できなければ1回分として扱う`() {
        val body =
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(
                        EachNode(
                            iterable = Expression(PropertyRef(listOf("missing"))),
                            itemName = "item",
                            body = listOf(TextNode("once")),
                        ),
                    ),
                ),
            )

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.SYSTEM, "once"))
    }

    @Test
    fun `Block本文に入れ子のBlockNodeがあれば防御的にテキストとして平坦化する`() {
        val body =
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(BlockNode(BlockRole.USER, listOf(TextNode("nested")))),
                ),
            )

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.SYSTEM, "nested"))
    }

    @Test
    fun `Block本文の入れ子IncludeNode MacroCallNodeは防御的に空文字として扱う`() {
        val body =
            listOf(
                BlockNode(
                    BlockRole.SYSTEM,
                    listOf(TextNode("a"), IncludeNode(target = "frag"), MacroCallNode(name = "macro"), TextNode("b")),
                ),
            )

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.SYSTEM, "ab"))
    }

    @Test
    fun `トップレベルのIncludeNode MacroCallNodeは防御的に空文字として扱う`() {
        val body = listOf(TextNode("a"), IncludeNode(target = "frag"), MacroCallNode(name = "macro"), TextNode("b"))

        val result = engine.expand(body, BindingSet.empty(), ContextBindingSet.empty())

        result shouldBe listOf(RenderedMessage(MessageRole.USER, "ab"))
    }
}
