package promptengine.engine.parser

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.BooleanLiteral
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.FilterCall
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.NumberLiteral
import promptengine.domain.template.ast.ParseErrorKind
import promptengine.domain.template.ast.PromptDslParseException
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.StringLiteral
import promptengine.domain.template.ast.TextNode

class PromptDslParserTest {
    private val parser = PromptDslParser()

    private fun prompt(
        body: String,
        frontMatter: String = "pe: \"1\"\nkind: prompt\nkey: t/x\n",
    ) = "---\n$frontMatter---\n$body"

    @Test
    fun `Front Matterのフィールドはfieldsマップとして保持される`() {
        val document =
            parser.parse(
                prompt(
                    body = "hello",
                    frontMatter = "pe: \"1\"\nkind: prompt\nkey: support/faq\nname: FAQ\ntags: [faq, customer]\n",
                ),
            )

        document.frontMatter.fields["pe"] shouldBe "1"
        document.frontMatter.fields["kind"] shouldBe "prompt"
        document.frontMatter.fields["key"] shouldBe "support/faq"
        document.frontMatter.fields["name"] shouldBe "FAQ"
        document.frontMatter.fields["tags"] shouldBe listOf("faq", "customer")
    }

    @Test
    fun `Front Matterが空でもPromptFrontMatterは空マップとして生成される`() {
        val document = parser.parse(prompt(body = "hello", frontMatter = ""))

        document.frontMatter.fields shouldBe emptyMap()
    }

    @Test
    fun `本文がプレーンテキストのみならTextNode1件になる`() {
        val document = parser.parse(prompt(body = "こんにちは、世界。"))

        document.body shouldBe listOf(TextNode("こんにちは、世界。"))
    }

    @Test
    fun `プロパティ参照式はドット区切りのPropertyRefになる`() {
        val document = parser.parse(prompt(body = "{{ context.application.serviceName }}"))

        document.body shouldBe
            listOf(ExprNode(Expression(PropertyRef(listOf("context", "application", "serviceName")))))
    }

    @Test
    fun `パイプフィルタは文字列 数値 真偽値リテラルを引数に取れる`() {
        val document =
            parser.parse(
                prompt(body = "{{ name | upper | truncate(100) | default(true) | fallback(\"guest\") }}"),
            )

        document.body shouldBe
            listOf(
                ExprNode(
                    Expression(
                        operand = PropertyRef(listOf("name")),
                        filters =
                            listOf(
                                FilterCall("upper"),
                                FilterCall("truncate", listOf(NumberLiteral(100.0))),
                                FilterCall("default", listOf(BooleanLiteral(true))),
                                FilterCall("fallback", listOf(StringLiteral("guest"))),
                            ),
                    ),
                ),
            )
    }

    @Test
    fun `ifブロックはelseなしだとelseBranchが空リストになる`() {
        val document = parser.parse(prompt(body = "{{#if flag}}yes{{/if}}"))

        document.body shouldBe
            listOf(
                IfNode(
                    condition = Expression(PropertyRef(listOf("flag"))),
                    thenBranch = listOf(TextNode("yes")),
                    elseBranch = emptyList(),
                ),
            )
    }

    @Test
    fun `ifブロックはelse節を持てる`() {
        val document = parser.parse(prompt(body = "{{#if flag}}yes{{else}}no{{/if}}"))

        document.body shouldBe
            listOf(
                IfNode(
                    condition = Expression(PropertyRef(listOf("flag"))),
                    thenBranch = listOf(TextNode("yes")),
                    elseBranch = listOf(TextNode("no")),
                ),
            )
    }

    @Test
    fun `eachブロックはiterableとitemNameとbodyを持つ`() {
        val document = parser.parse(prompt(body = "{{#each examples as ex}}{{ ex.q }}{{/each}}"))

        document.body shouldBe
            listOf(
                EachNode(
                    iterable = Expression(PropertyRef(listOf("examples"))),
                    itemName = "ex",
                    body = listOf(ExprNode(Expression(PropertyRef(listOf("ex", "q"))))),
                ),
            )
    }

    @Test
    fun `blockのroleはsystem user assistantのいずれかに解決される`() {
        val document = parser.parse(prompt(body = "{{#block assistant}}hi{{/block}}"))

        document.body shouldBe listOf(BlockNode(BlockRole.ASSISTANT, listOf(TextNode("hi"))))
    }

    @Test
    fun `includeタグはtargetとversionRangeとbindingsを解決する`() {
        val document =
            parser.parse(prompt(body = "{{> fragments/safety-policy@^2 tone=formal count=3 }}"))

        document.body shouldBe
            listOf(
                IncludeNode(
                    target = "fragments/safety-policy",
                    versionRange = "^2",
                    bindings =
                        mapOf(
                            "tone" to Expression(PropertyRef(listOf("formal"))),
                            "count" to Expression(NumberLiteral(3.0)),
                        ),
                ),
            )
    }

    @Test
    fun `includeタグはversionRangeとbindingsを省略できる`() {
        val document = parser.parse(prompt(body = "{{> safety }}"))

        document.body shouldBe listOf(IncludeNode(target = "safety", versionRange = null, bindings = emptyMap()))
    }

    @Test
    fun `macro呼出は名前付き引数のマップになる`() {
        val document = parser.parse(prompt(body = "{{ bulletList(items=faqItems) }}"))

        document.body shouldBe
            listOf(MacroCallNode("bulletList", mapOf("items" to Expression(PropertyRef(listOf("faqItems"))))))
    }

    @Test
    fun `コメントはASTに現れず読み飛ばされる`() {
        val document = parser.parse(prompt(body = "前{{!-- これはコメント --}}後"))

        document.body shouldBe listOf(TextNode("前後"))
    }

    @Test
    fun `同一入力を複数回パースすると構造的に等価なPromptDocumentが得られる`() {
        val source =
            prompt(
                body =
                    "{{#block user}}{{#if x}}{{#each xs as x1}}{{ x1 | upper }}{{/each}}{{else}}n{{/if}}{{/block}}",
            )

        parser.parse(source) shouldBe parser.parse(source)
    }

    @Test
    fun `空タグはSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
    }

    @Test
    fun `閉じ角括弧の無いタグはSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ name")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unterminated tag")
    }

    @Test
    fun `eachにasが無いとSYNTAX_ERRORになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{#each examples}}x{{/each}}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
    }

    @Test
    fun `対応する開きタグの無い閉じタグはSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{/if}}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unexpected")
    }

    @Test
    fun `式の先頭に許可されない文字があるとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ #invalid }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
    }

    @Test
    fun `入力サイズが上限を超えるとINPUT_TOO_LARGEになる`() {
        val smallLimitParser = PromptDslParser(PromptDslParserConfig(maxInputSizeChars = 10))

        val exception = shouldThrow<PromptDslParseException> { smallLimitParser.parse(prompt(body = "hello")) }

        exception.error.kind shouldBe ParseErrorKind.INPUT_TOO_LARGE
        exception.error.line shouldBe 1
        exception.error.column shouldBe 1
    }

    @Test
    fun `maxNestingDepthを引き下げるとより浅い段数でNESTING_TOO_DEEPになる`() {
        val shallowParser = PromptDslParser(PromptDslParserConfig(maxNestingDepth = 1))

        val exception =
            shouldThrow<PromptDslParseException> {
                shallowParser.parse(prompt(body = "{{#block user}}{{#if a}}x{{/if}}{{/block}}"))
            }

        exception.error.kind shouldBe ParseErrorKind.NESTING_TOO_DEEP
    }

    // --- 分岐カバレッジ監査で追加したテスト群 ---

    @Test
    fun `引数無しのフィルタ呼出は空の引数リストになる`() {
        val document = parser.parse(prompt(body = "{{ name | trim() }}"))

        document.body shouldBe
            listOf(ExprNode(Expression(PropertyRef(listOf("name")), listOf(FilterCall("trim")))))
    }

    @Test
    fun `フィルタ引数はカンマ区切りで複数取れる`() {
        val document = parser.parse(prompt(body = "{{ value | clamp(1, 10) }}"))

        document.body shouldBe
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("value")),
                        listOf(FilterCall("clamp", listOf(NumberLiteral(1.0), NumberLiteral(10.0)))),
                    ),
                ),
            )
    }

    @Test
    fun `シングルクォートの文字列リテラルを解釈できる`() {
        val document = parser.parse(prompt(body = "{{ name | fallback('guest') }}"))

        document.body shouldBe
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("name")),
                        listOf(FilterCall("fallback", listOf(StringLiteral("guest")))),
                    ),
                ),
            )
    }

    @Test
    fun `文字列リテラル内のエスケープされたクォートはリテラル文字として解釈される`() {
        val document = parser.parse(prompt(body = "{{ name | fallback(\"say \\\"hi\\\"\") }}"))

        document.body shouldBe
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("name")),
                        listOf(FilterCall("fallback", listOf(StringLiteral("say \"hi\"")))),
                    ),
                ),
            )
    }

    @Test
    fun `負の数値と小数の数値リテラルを解釈できる`() {
        val document = parser.parse(prompt(body = "{{ value | clamp(-5, 2.5) }}"))

        document.body shouldBe
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("value")),
                        listOf(FilterCall("clamp", listOf(NumberLiteral(-5.0), NumberLiteral(2.5)))),
                    ),
                ),
            )
    }

    @Test
    fun `bareword false は真偽値リテラルとして解釈される`() {
        val document = parser.parse(prompt(body = "{{ flag | default(false) }}"))

        document.body shouldBe
            listOf(
                ExprNode(
                    Expression(
                        PropertyRef(listOf("flag")),
                        listOf(FilterCall("default", listOf(BooleanLiteral(false)))),
                    ),
                ),
            )
    }

    @Test
    fun `アンダースコアで始まる識別子をプロパティ参照として解釈できる`() {
        val document = parser.parse(prompt(body = "{{ _internal }}"))

        document.body shouldBe listOf(ExprNode(Expression(PropertyRef(listOf("_internal")))))
    }

    @Test
    fun `数字が続かないハイフン単体はSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ -x }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unexpected character")
    }

    @Test
    fun `式の後ろに余分な文字が続くとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ name extra }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("trailing characters")
    }

    @Test
    fun `プロパティパスの末尾がドットで終わるとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ a. }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("expected an identifier")
    }

    @Test
    fun `パイプの後にフィルタ名が無いとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ name | }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("expected an identifier")
    }

    @Test
    fun `includeのbinding値が空だとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{> safety tone= }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("expected a value")
    }

    @Test
    fun `includeのbinding値の文字列内にエスケープされたクォートと空白があっても1トークンとして解釈される`() {
        val document = parser.parse(prompt(body = "{{> safety note=\"a\\\" b\" }}"))

        document.body shouldBe
            listOf(
                IncludeNode(
                    target = "safety",
                    versionRange = null,
                    bindings = mapOf("note" to Expression(StringLiteral("a\" b"))),
                ),
            )
    }

    @Test
    fun `includeのbinding値に空白を含む文字列を渡せる`() {
        val document = parser.parse(prompt(body = "{{> safety note=\"hello world\" }}"))

        document.body shouldBe
            listOf(
                IncludeNode(
                    target = "safety",
                    versionRange = null,
                    bindings = mapOf("note" to Expression(StringLiteral("hello world"))),
                ),
            )
    }

    @Test
    fun `macro呼出はカンマ区切りで複数引数を取れ 引数値の文字列内のカンマは分割されない`() {
        val document = parser.parse(prompt(body = "{{ greet(name=\"Doe, Jane\", title=pageTitle) }}"))

        document.body shouldBe
            listOf(
                MacroCallNode(
                    "greet",
                    mapOf(
                        "name" to Expression(StringLiteral("Doe, Jane")),
                        "title" to Expression(PropertyRef(listOf("pageTitle"))),
                    ),
                ),
            )
    }

    @Test
    fun `閉じられていないコメントはSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{!-- oops }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unterminated comment")
    }

    @Test
    fun `Front Matterの開始デリミタが無いとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse("no front matter here") }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("must start with")
    }

    @Test
    fun `Front MatterがYAMLマッピングでない場合はSYNTAX_ERRORになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "hello", frontMatter = "- a\n- b\n")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("must be a YAML mapping")
    }

    @Test
    fun `Front Matterのキーが文字列以外だとSYNTAX_ERRORになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "hello", frontMatter = "1: a\n")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("keys must be strings")
    }

    @Test
    fun `Front Matterが末尾行で未終端のflowシーケンスだとYAML構文エラーになる`() {
        val exception =
            shouldThrow<PromptDslParseException> {
                parser.parse(prompt(body = "hello", frontMatter = "pe: \"1\"\nkind: prompt\ntags: [a, b\n"))
            }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("YAML")
    }

    // --- 分岐カバレッジ監査 第2ラウンド（unterminated系・区切り文字系のギャップ） ---

    @Test
    fun `elseの後に if の閉じタグが無いと未終端エラーになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{#if x}}a{{else}}b")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unterminated '{{#if}}'")
    }

    @Test
    fun `eachの閉じタグが無いと未終端エラーになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{#each xs as x}}a")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unterminated '{{#each}}'")
    }

    @Test
    fun `blockの閉じタグが無いと未終端エラーになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{#block user}}a")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unterminated '{{#block}}'")
    }

    @Test
    fun `条件式の無いif開始タグはSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{#if}}a{{/if}}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("requires a condition expression")
    }

    @Test
    fun `短すぎるコメントタグはSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{!--}}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unterminated comment")
    }

    @Test
    fun `フィルタ引数リストが閉じられていないとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ name | trim( }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
    }

    @Test
    fun `フィルタ引数の区切りにカンマが無いとSYNTAX_ERRORになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ value | clamp(1 2) }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("expected ',' or ')'")
    }

    @Test
    fun `直後に何も続かないハイフン単体はSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ - }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unexpected character")
    }

    @Test
    fun `ファイル末尾がクォート内エスケープの直後で終わるとタグ未終端エラーになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{> safety note=\"a\\")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("unterminated tag")
    }

    @Test
    fun `eachのitem名が識別子として不正だとSYNTAX_ERRORになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{#each xs as 1x}}a{{/each}}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
    }

    @Test
    fun `includeのtargetが空白のみだとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{>    }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("include target must not be empty")
    }

    @Test
    fun `includeのtargetが先頭に@のみでversionRangeしか無いとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{> @1.0 }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("include target must not be empty")
    }

    @Test
    fun `includeのbindingトークンに等号が無いとSYNTAX_ERRORになる`() {
        val exception =
            shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{> safety badtoken }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("expected 'k=v' binding")
    }

    @Test
    fun `macro引数に等号が無いとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ foo(bareword) }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("name=value")
    }

    @Test
    fun `includeのbinding値はシングルクォートの文字列も取れ 連続する空白は無視される`() {
        val document = parser.parse(prompt(body = "{{>  safety  note='hi' }}"))

        document.body shouldBe
            listOf(
                IncludeNode(
                    target = "safety",
                    versionRange = null,
                    bindings = mapOf("note" to Expression(StringLiteral("hi"))),
                ),
            )
    }

    @Test
    fun `macro引数はシングルクォートの文字列も取れる`() {
        val document = parser.parse(prompt(body = "{{ greet(name='Jane') }}"))

        document.body shouldBe
            listOf(MacroCallNode("greet", mapOf("name" to Expression(StringLiteral("Jane")))))
    }

    @Test
    fun `macro引数リストの末尾カンマは無視される`() {
        val document = parser.parse(prompt(body = "{{ foo(a=x,) }}"))

        document.body shouldBe
            listOf(MacroCallNode("foo", mapOf("a" to Expression(PropertyRef(listOf("x"))))))
    }

    @Test
    fun `macro引数の文字列内のエスケープされたクォートはリテラル文字として解釈される`() {
        val document = parser.parse(prompt(body = "{{ greet(name=\"a\\\" b\") }}"))

        document.body shouldBe
            listOf(MacroCallNode("greet", mapOf("name" to Expression(StringLiteral("a\" b")))))
    }

    @Test
    fun `ドットの直後が識別子として不正な文字だとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ a.1x }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("expected an identifier")
    }

    @Test
    fun `パイプの直後が識別子として不正な文字だとSYNTAX_ERRORになる`() {
        val exception = shouldThrow<PromptDslParseException> { parser.parse(prompt(body = "{{ name | 5x }}")) }

        exception.error.kind shouldBe ParseErrorKind.SYNTAX_ERROR
        exception.error.message.shouldContain("expected an identifier")
    }

    @Test
    fun `引数無しのmacro呼出は空の引数マップになる`() {
        val document = parser.parse(prompt(body = "{{ foo() }}"))

        document.body shouldBe listOf(MacroCallNode("foo", emptyMap()))
    }
}
