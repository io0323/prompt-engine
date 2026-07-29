package promptengine.engine.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.variable.BindingSet
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableType

class SchemaValidationRuleTest {
    private val rule = SchemaValidationRule()

    private fun compiledPrompt(variables: List<VariableDefinition>): CompiledPrompt =
        CompiledPrompt(
            body = listOf(TextNode("hello")),
            dependencies = emptyList(),
            variables = variables,
            contextRequirements = emptyList(),
        )

    @Test
    fun `束縛値が宣言型に一致すればFindingを返さない`() {
        val compiled =
            compiledPrompt(
                listOf(
                    VariableDefinition(name = "productName", type = VariableType.STRING),
                    VariableDefinition(name = "count", type = VariableType.NUMBER),
                ),
            )
        val bindings = BindingSet(mapOf("productName" to "widget", "count" to 3))

        val findings = rule.validate(compiled, bindings, ContextBindingSet.empty())

        findings shouldBe emptyList()
    }

    @Test
    fun `束縛値が宣言型と異なればERRORのFindingを返す`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "count", type = VariableType.NUMBER)))
        val bindings = BindingSet(mapOf("count" to "not-a-number"))

        val findings = rule.validate(compiled, bindings, ContextBindingSet.empty())

        findings shouldBe
            listOf(
                Finding(
                    ruleId = "SchemaValidation",
                    path = "$.parameters.count",
                    severity = Severity.ERROR,
                    message = "value does not match declared type NUMBER for variable 'count'",
                ),
            )
    }

    @Test
    fun `ARRAY型宣言に対しリストでない値が束縛されていればFindingを返す`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "items", type = VariableType.ARRAY)))
        val bindings = BindingSet(mapOf("items" to "not-a-list"))

        val findings = rule.validate(compiled, bindings, ContextBindingSet.empty())

        findings.single().severity shouldBe Severity.ERROR
    }

    @Test
    fun `束縛値が存在しない変数は検証対象にならない Compile-only相当`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "count", type = VariableType.NUMBER)))

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings shouldBe emptyList()
    }

    @Test
    fun `BOOLEAN型宣言に真偽値が束縛されていればFindingを返さない`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "flag", type = VariableType.BOOLEAN)))
        val bindings = BindingSet(mapOf("flag" to true))

        rule.validate(compiled, bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `BOOLEAN型宣言に真偽値でない値が束縛されていればFindingを返す`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "flag", type = VariableType.BOOLEAN)))
        val bindings = BindingSet(mapOf("flag" to "not-a-boolean"))

        rule.validate(compiled, bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `OBJECT型宣言にMapが束縛されていればFindingを返さない`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "config", type = VariableType.OBJECT)))
        val bindings = BindingSet(mapOf("config" to mapOf("k" to "v")))

        rule.validate(compiled, bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `OBJECT型宣言にMapでない値が束縛されていればFindingを返す`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "config", type = VariableType.OBJECT)))
        val bindings = BindingSet(mapOf("config" to "not-a-map"))

        rule.validate(compiled, bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `STRING型宣言に文字列でもSensitiveValueでもない値が束縛されていればFindingを返す`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "productName", type = VariableType.STRING)))
        val bindings = BindingSet(mapOf("productName" to 42))

        rule.validate(compiled, bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `STRING型宣言にSensitiveValueが束縛されていてもFindingを返さない`() {
        val compiled = compiledPrompt(listOf(VariableDefinition(name = "apiKeyRef", type = VariableType.STRING)))
        val bindings = BindingSet(mapOf("apiKeyRef" to SensitiveValue.of("secret-value")))

        rule.validate(compiled, bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }
}
