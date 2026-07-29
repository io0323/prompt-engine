package promptengine.plugin.validator.policy

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.BindingSet

class PolicyValidationRuleTest {
    private val rule = PolicyValidationRule(bannedWords = listOf("confidential", "secret-project"))

    private fun compiledPrompt(
        text: String,
        policies: List<String>,
    ): CompiledPrompt =
        CompiledPrompt(
            body = listOf(TextNode(text)),
            dependencies = emptyList(),
            variables = emptyList(),
            contextRequirements = emptyList(),
            validation = ValidationSettings(policies = policies),
        )

    private fun compiledPrompt(
        body: List<PromptAst>,
        policies: List<String>,
    ): CompiledPrompt =
        CompiledPrompt(
            body = body,
            dependencies = emptyList(),
            variables = emptyList(),
            contextRequirements = emptyList(),
            validation = ValidationSettings(policies = policies),
        )

    @Test
    fun `id severity は既定値を持つ`() {
        rule.id() shouldBe "no-pii"
        rule.severity() shouldBe Severity.ERROR
    }

    @Test
    fun `ruleId ruleSeverity を明示的に指定できる`() {
        val custom =
            PolicyValidationRule(bannedWords = listOf("x"), ruleId = "corporate-tone", ruleSeverity = Severity.WARNING)

        custom.id() shouldBe "corporate-tone"
        custom.severity() shouldBe Severity.WARNING
    }

    @Test
    fun `禁止語を含まないテキストはFindingを出さない`() {
        val compiled = compiledPrompt("hello world", policies = listOf("no-pii"))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `禁止語を含みvalidation policiesに自分のidが含まれていればERRORのFindingを出す`() {
        val compiled = compiledPrompt("this project is confidential", policies = listOf("no-pii"))

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings.single().severity shouldBe Severity.ERROR
        findings.single().message shouldBe "banned word detected: 'confidential'"
    }

    @Test
    fun `禁止語を含んでいてもvalidation policiesに自分のidが無ければFindingを出さない`() {
        val compiled = compiledPrompt("this project is confidential", policies = listOf("corporate-tone"))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `大文字小文字を区別せず禁止語を検出する`() {
        val compiled = compiledPrompt("This Is CONFIDENTIAL info", policies = listOf("no-pii"))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `複数の禁止語を含む場合は複数件のFindingを返す`() {
        val compiled = compiledPrompt("confidential secret-project details", policies = listOf("no-pii"))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()).size shouldBe 2
    }

    @Test
    fun `IfNodeのthen elseブランチ内の禁止語も検出する`() {
        val node =
            IfNode(
                condition = Expression(PropertyRef(listOf("flag"))),
                thenBranch = listOf(TextNode("confidential")),
                elseBranch = listOf(TextNode("secret-project")),
            )
        val compiled = compiledPrompt(listOf(node), policies = listOf("no-pii"))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()).size shouldBe 2
    }

    @Test
    fun `EachNodeのbody内の禁止語も検出する`() {
        val node =
            EachNode(
                iterable = Expression(PropertyRef(listOf("items"))),
                itemName = "item",
                body = listOf(TextNode("confidential")),
            )
        val compiled = compiledPrompt(listOf(node), policies = listOf("no-pii"))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `BlockNodeのbody内の禁止語も検出する`() {
        val node = BlockNode(BlockRole.SYSTEM, listOf(TextNode("confidential")))
        val compiled = compiledPrompt(listOf(node), policies = listOf("no-pii"))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `ExprNode IncludeNode MacroCallNodeは文字列に寄与せずクラッシュしない`() {
        val body =
            listOf(
                ExprNode(Expression(PropertyRef(listOf("productName")))),
                IncludeNode(target = "safety"),
                MacroCallNode(name = "bulletList"),
            )
        val compiled = compiledPrompt(body, policies = listOf("no-pii"))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }
}
