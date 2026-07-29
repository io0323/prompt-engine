package promptengine.engine.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.Severity
import promptengine.domain.variable.BindingSet
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableType

class ParameterValidationRuleTest {
    private val rule = ParameterValidationRule()

    private fun compiledPrompt(variable: VariableDefinition): CompiledPrompt =
        CompiledPrompt(
            body = listOf(TextNode("x")),
            dependencies = emptyList(),
            variables = listOf(variable),
            contextRequirements = emptyList(),
        )

    @Test
    fun `全ての制約を満たす値はFindingを出さない`() {
        val variable =
            VariableDefinition(
                name = "tone",
                type = VariableType.STRING,
                constraints = listOf("pattern:^[a-z]+$", "maxLength:20", "enum:polite,formal,casual"),
            )
        val bindings = BindingSet(mapOf("tone" to "polite"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `patternに一致しない値はERRORのFindingを出す`() {
        val variable =
            VariableDefinition(name = "tone", type = VariableType.STRING, constraints = listOf("pattern:^[a-z]+$"))
        val bindings = BindingSet(mapOf("tone" to "Polite123"))

        val findings = rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty())

        findings.single().severity shouldBe Severity.ERROR
    }

    @Test
    fun `min未満の数値はFindingを出す`() {
        val variable =
            VariableDefinition(name = "count", type = VariableType.NUMBER, constraints = listOf("min:1"))
        val bindings = BindingSet(mapOf("count" to 0))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `min以上の数値はFindingを出さない`() {
        val variable =
            VariableDefinition(name = "count", type = VariableType.NUMBER, constraints = listOf("min:1"))
        val bindings = BindingSet(mapOf("count" to 1))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `max超過の数値はFindingを出す`() {
        val variable =
            VariableDefinition(name = "count", type = VariableType.NUMBER, constraints = listOf("max:10"))
        val bindings = BindingSet(mapOf("count" to 11))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `max以下の数値はFindingを出さない`() {
        val variable =
            VariableDefinition(name = "count", type = VariableType.NUMBER, constraints = listOf("max:10"))
        val bindings = BindingSet(mapOf("count" to 10))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `pattern制約が文字列以外の値には適用されず無視される`() {
        val variable =
            VariableDefinition(name = "count", type = VariableType.NUMBER, constraints = listOf("pattern:^[a-z]+$"))
        val bindings = BindingSet(mapOf("count" to 42))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `min max制約が数値以外の値には適用されず無視される`() {
        val variable =
            VariableDefinition(name = "tone", type = VariableType.STRING, constraints = listOf("min:1", "max:10"))
        val bindings = BindingSet(mapOf("tone" to "polite"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `maxLength制約が文字列以外の値には適用されず無視される`() {
        val variable =
            VariableDefinition(name = "count", type = VariableType.NUMBER, constraints = listOf("maxLength:1"))
        val bindings = BindingSet(mapOf("count" to 42))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `コロンを含まない制約文字列は無視される`() {
        val variable =
            VariableDefinition(name = "tone", type = VariableType.STRING, constraints = listOf("malformed"))
        val bindings = BindingSet(mapOf("tone" to "polite"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `constraintsが空の変数は束縛値があっても検証対象にならない`() {
        val variable = VariableDefinition(name = "tone", type = VariableType.STRING, constraints = emptyList())
        val bindings = BindingSet(mapOf("tone" to "polite"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `enumに含まれない値はFindingを出す`() {
        val variable =
            VariableDefinition(name = "tone", type = VariableType.STRING, constraints = listOf("enum:polite,formal"))
        val bindings = BindingSet(mapOf("tone" to "casual"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `maxLength超過の文字列はFindingを出す`() {
        val variable =
            VariableDefinition(
                name = "productName",
                type = VariableType.STRING,
                constraints = listOf("maxLength:5"),
            )
        val bindings = BindingSet(mapOf("productName" to "too long a value"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `未知の制約キーは無視される`() {
        val variable =
            VariableDefinition(
                name = "tone",
                type = VariableType.STRING,
                constraints = listOf("unknownKey:whatever"),
            )
        val bindings = BindingSet(mapOf("tone" to "polite"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `束縛値が存在しない変数は検証対象にならない`() {
        val variable =
            VariableDefinition(name = "tone", type = VariableType.STRING, constraints = listOf("maxLength:1"))

        rule.validate(compiledPrompt(variable), BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `不正なmin制約値は例外を投げずFindingを返す`() {
        val variable =
            VariableDefinition(name = "count", type = VariableType.NUMBER, constraints = listOf("min:notanumber"))
        val bindings = BindingSet(mapOf("count" to 5))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `不正なmax制約値は例外を投げずFindingを返す`() {
        val variable =
            VariableDefinition(name = "count", type = VariableType.NUMBER, constraints = listOf("max:notanumber"))
        val bindings = BindingSet(mapOf("count" to 5))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `不正なmaxLength制約値は例外を投げずFindingを返す`() {
        val variable =
            VariableDefinition(name = "tone", type = VariableType.STRING, constraints = listOf("maxLength:notanumber"))
        val bindings = BindingSet(mapOf("tone" to "polite"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()).size shouldBe 1
    }

    @Test
    fun `不正な正規表現のpattern制約は例外を投げずFindingを返す`() {
        val variable =
            VariableDefinition(name = "tone", type = VariableType.STRING, constraints = listOf("pattern:["))
        val bindings = BindingSet(mapOf("tone" to "polite"))

        rule.validate(compiledPrompt(variable), bindings, ContextBindingSet.empty()).size shouldBe 1
    }
}
