package promptengine.domain.composition

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.context.ContextRequirement
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableType

class CompiledPromptTest {
    @Test
    fun `body 依存一覧 変数定義 Context要件を保持する`() {
        val dependency =
            ResolvedDependency.TemplateDependency(
                TemplateKey("templates/base-assistant"),
                VersionRange.CaretMajor(2),
                SemVer(2, 0, 0),
                PublicationState.Published,
                "hash",
            )
        val variable = VariableDefinition(name = "tone", type = VariableType.STRING, required = false)
        val contextRequirement = ContextRequirement(scope = "user", required = listOf("userId"))

        val compiled =
            CompiledPrompt(
                body = listOf(TextNode("hello")),
                dependencies = listOf(dependency),
                variables = listOf(variable),
                contextRequirements = listOf(contextRequirement),
            )

        compiled.body shouldBe listOf(TextNode("hello"))
        compiled.dependencies shouldBe listOf(dependency)
        compiled.variables shouldBe listOf(variable)
        compiled.contextRequirements shouldBe listOf(contextRequirement)
        compiled.validation shouldBe ValidationSettings()
    }

    @Test
    fun `validation を明示的に指定して保持できる`() {
        val settings = ValidationSettings(maxLength = 500, placeholders = PlaceholderMode.STRICT)

        val compiled =
            CompiledPrompt(
                body = listOf(TextNode("hello")),
                dependencies = emptyList(),
                variables = emptyList(),
                contextRequirements = emptyList(),
                validation = settings,
            )

        compiled.validation shouldBe settings
    }

    @Test
    fun `同一リポジトリ状態から得た2つのCompiledPromptは構造的に等しい 決定性`() {
        val a = CompiledPrompt(listOf(TextNode("x")), emptyList(), emptyList(), emptyList())
        val b = CompiledPrompt(listOf(TextNode("x")), emptyList(), emptyList(), emptyList())

        a shouldBe b
    }
}
