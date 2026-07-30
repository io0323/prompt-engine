package promptengine.engine.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.Severity
import promptengine.domain.variable.BindingSet

class DependencyValidationRuleTest {
    private val rule = DependencyValidationRule()

    private fun templateDependency(status: PublicationState) =
        ResolvedDependency.TemplateDependency(
            key = TemplateKey("templates/base-assistant"),
            requestedRange = VersionRange.CaretMajor(2),
            resolvedVersion = SemVer(2, 0, 0),
            status = status,
            contentHash = "hash",
        )

    private fun fragmentDependency(status: PublicationState) =
        ResolvedDependency.FragmentDependency(
            key = FragmentKey("fragments/safety-policy"),
            requestedRange = VersionRange.CaretMajor(1),
            resolvedVersion = SemVer(1, 0, 0),
            status = status,
            contentHash = "hash",
        )

    private fun compiledPrompt(dependencies: List<ResolvedDependency>): CompiledPrompt =
        CompiledPrompt(
            body = listOf(TextNode("x")),
            dependencies = dependencies,
            variables = emptyList(),
            contextRequirements = emptyList(),
        )

    @Test
    fun `全依存がPublishedならFindingを出さない`() {
        val compiled = compiledPrompt(listOf(templateDependency(PublicationState.Published)))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `Template依存がDraftならWARNINGのFindingを出す COMPILE_ONLYで許可された場合`() {
        val compiled = compiledPrompt(listOf(templateDependency(PublicationState.Draft)))

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings.single().severity shouldBe Severity.WARNING
        findings.single().path shouldBe "$.dependencies.templates.templates/base-assistant"
    }

    @Test
    fun `Fragment依存がDraftならWARNINGのFindingを出す`() {
        val compiled = compiledPrompt(listOf(fragmentDependency(PublicationState.Draft)))

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings.single().severity shouldBe Severity.WARNING
        findings.single().path shouldBe "$.dependencies.fragments.fragments/safety-policy"
    }

    @Test
    fun `依存が無ければFindingを出さない`() {
        val compiled = compiledPrompt(emptyList())

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }
}
