package promptengine.engine.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.BindingSet

class ValidationEngineImplTest {
    private val compiled =
        CompiledPrompt(
            body = listOf(TextNode("x")),
            dependencies = emptyList(),
            variables = emptyList(),
            contextRequirements = emptyList(),
        )

    private fun ruleReturning(vararg findings: Finding): ValidationRule =
        object : ValidationRule {
            override fun id(): String = "Fake"

            override fun severity(): Severity = Severity.ERROR

            override fun validate(
                compiled: CompiledPrompt,
                variableBindings: BindingSet,
                contextBindings: ContextBindingSet,
            ): List<Finding> = findings.toList()
        }

    @Test
    fun `全Ruleを実行し1件目で止めずFindingを集約する`() {
        val findingA = Finding("RuleA", "$.a", Severity.WARNING, "warn")
        val findingB = Finding("RuleB", "$.b", Severity.ERROR, "error")
        val engine = ValidationEngineImpl(listOf(ruleReturning(findingA), ruleReturning(findingB)))

        val report = engine.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        report.findings shouldBe listOf(findingA, findingB)
    }

    @Test
    fun `ERROR Findingが1件も無ければ hasErrors は false`() {
        val engine = ValidationEngineImpl(listOf(ruleReturning(Finding("RuleA", "$.a", Severity.WARNING, "warn"))))

        val report = engine.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        report.hasErrors shouldBe false
    }

    @Test
    fun `ERROR Findingが1件でもあれば hasErrors は true`() {
        val engine = ValidationEngineImpl(listOf(ruleReturning(Finding("RuleA", "$.a", Severity.ERROR, "error"))))

        val report = engine.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        report.hasErrors shouldBe true
    }

    @Test
    fun `Ruleが無ければ空のReportを返す`() {
        val engine = ValidationEngineImpl(emptyList())

        val report = engine.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        report.findings shouldBe emptyList()
    }
}
