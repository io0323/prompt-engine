package promptengine.engine.validation

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.context.ContextRequirement
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.Finding
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.BindingSet
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableType

class PlaceholderValidationRuleTest {
    private val rule = PlaceholderValidationRule()

    private fun exprNode(vararg path: String) = ExprNode(Expression(PropertyRef(path.toList())))

    @Test
    fun `severity は既定でERRORを返す`() {
        rule.severity() shouldBe Severity.ERROR
    }

    @Test
    fun `id は PlaceholderValidation を返す`() {
        rule.id() shouldBe "PlaceholderValidation"
    }

    @Test
    fun `宣言済み変数が参照されていれば未束縛未使用いずれのFindingも出さない`() {
        val compiled =
            CompiledPrompt(
                body = listOf(TextNode("Answer: "), exprNode("productName")),
                dependencies = emptyList(),
                variables = listOf(VariableDefinition(name = "productName", type = VariableType.STRING)),
                contextRequirements = emptyList(),
            )

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings shouldBe emptyList()
    }

    @Test
    fun `未宣言変数を参照するとstrictではERRORのFindingを出す`() {
        val compiled =
            CompiledPrompt(
                body = listOf(exprNode("producName")),
                dependencies = emptyList(),
                variables = emptyList(),
                contextRequirements = emptyList(),
                validation = ValidationSettings(placeholders = PlaceholderMode.STRICT),
            )

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings shouldContain
            Finding(
                ruleId = "PlaceholderValidation",
                path = "$.parameters.producName",
                severity = Severity.ERROR,
                message = "unbound placeholder: 'producName' is not declared as a variable",
            )
    }

    @Test
    fun `未宣言変数を参照してもlenientではWARNINGのFindingを出す`() {
        val compiled =
            CompiledPrompt(
                body = listOf(exprNode("producName")),
                dependencies = emptyList(),
                variables = emptyList(),
                contextRequirements = emptyList(),
                validation = ValidationSettings(placeholders = PlaceholderMode.LENIENT),
            )

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings.single().severity shouldBe Severity.WARNING
    }

    @Test
    fun `宣言済みだが本文で参照されない変数は未使用としてFindingを出す`() {
        val compiled =
            CompiledPrompt(
                body = listOf(TextNode("no placeholders here")),
                dependencies = emptyList(),
                variables = listOf(VariableDefinition(name = "unused", type = VariableType.STRING)),
                contextRequirements = emptyList(),
                validation = ValidationSettings(placeholders = PlaceholderMode.STRICT),
            )

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings shouldContain
            Finding(
                ruleId = "PlaceholderValidation",
                path = "$.variables.unused",
                severity = Severity.ERROR,
                message = "declared variable is never referenced: 'unused'",
            )
    }

    @Test
    fun `未宣言Contextスコープを参照すると strict lenient に関わらず常にERROR`() {
        val compiled =
            CompiledPrompt(
                body = listOf(BlockNode(BlockRole.SYSTEM, listOf(exprNode("context", "workflow", "step")))),
                dependencies = emptyList(),
                variables = emptyList(),
                contextRequirements = listOf(ContextRequirement(scope = "user", required = listOf("id"))),
                validation = ValidationSettings(placeholders = PlaceholderMode.LENIENT),
            )

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings shouldContain
            Finding(
                ruleId = "PlaceholderValidation",
                path = "$.context.workflow",
                severity = Severity.ERROR,
                message = "reference to undeclared context scope: workflow",
            )
    }

    @Test
    fun `宣言済みContextスコープを参照してもFindingを出さない`() {
        val compiled =
            CompiledPrompt(
                body = listOf(exprNode("context", "user", "id")),
                dependencies = emptyList(),
                variables = emptyList(),
                contextRequirements = listOf(ContextRequirement(scope = "user", required = listOf("id"))),
            )

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings shouldBe emptyList()
    }

    @Test
    fun `scope部分を持たないcontext参照はFindingを出さない`() {
        val compiled =
            CompiledPrompt(
                body = listOf(exprNode("context")),
                dependencies = emptyList(),
                variables = emptyList(),
                contextRequirements = emptyList(),
            )

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings shouldBe emptyList()
    }
}
