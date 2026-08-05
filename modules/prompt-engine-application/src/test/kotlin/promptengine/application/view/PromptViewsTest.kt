package promptengine.application.view

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.query.GetPromptResult
import promptengine.domain.context.ContextRequirement
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptSummary
import promptengine.domain.prompt.PromptVersionDiff
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.Page
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.TemplateKey
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import java.time.Instant

class PromptViewsTest {
    private val eventContext = EventContext(actor = "user:test", traceId = "trace-1", occurredAt = Instant.EPOCH)

    private val key = PromptKey("support/faq-answer")
    private val semVer = SemVer(1, 0, 0)

    private fun promptVersion(
        variables: List<VariableDefinition> = emptyList(),
        contextRequirements: List<ContextRequirement> = emptyList(),
        extends: ExtendsRef? = null,
        validation: ValidationSettings = ValidationSettings(),
        output: OutputDeclaration? = null,
    ) = Prompt.create(
        key,
        NewPromptVersion(
            semVer,
            PromptContent("---\nkey: ${key.value}\n---\nhello"),
            variables,
            contextRequirements,
            extends,
            validation,
            output,
        ),
        eventContext,
    ).first.versions.first()

    @Test
    fun `Page-toView は items-page-size-totalElementsを変換しmapperを各itemへ適用する`() {
        val page = Page(items = listOf(1, 2, 3), page = 0, size = 20, totalElements = 3)

        val view = page.toView { it * 10 }

        view.items shouldBe listOf(10, 20, 30)
        view.page shouldBe 0
        view.size shouldBe 20
        view.totalElements shouldBe 3
    }

    @Test
    fun `VariableDefinition-toView は全フィールドを変換する`() {
        val variable =
            VariableDefinition(
                name = "x",
                type = VariableType.STRING,
                source = VariableSource.RUNTIME,
                required = true,
                default = "d",
                constraints = listOf("min:1"),
                sensitive = false,
            )

        val view = variable.toView()

        view.name shouldBe "x"
        view.type shouldBe "STRING"
        view.source shouldBe "RUNTIME"
        view.required shouldBe true
        view.default shouldBe "d"
        view.constraints shouldBe listOf("min:1")
        view.sensitive shouldBe false
    }

    @Test
    fun `PromptVersion-toView は最小構成のVersionをViewへ変換する`() {
        val version = promptVersion()

        val view = version.toView()

        view.semVer shouldBe "1.0.0"
        view.state shouldBe "Draft"
        view.variables shouldBe emptyList()
        view.contextRequirements shouldBe emptyList()
        view.extends shouldBe null
        view.output shouldBe null
        view.validation.placeholders shouldBe PlaceholderMode.LENIENT.name
    }

    @Test
    fun `PromptVersion-toView はvariables-contextRequirements-extends-outputが揃った構成を変換する`() {
        @OptIn(ExtendsRefApi::class)
        val extends = ExtendsRef(TemplateKey("templates/base"), VersionRange.Exact(SemVer(2, 0, 0)))
        val version =
            promptVersion(
                variables = listOf(VariableDefinition(name = "x", type = VariableType.STRING)),
                contextRequirements = listOf(ContextRequirement(scope = "conversation")),
                extends = extends,
                output = OutputDeclaration(OutputFormat.JSON, "schemas/x"),
            )

        val view = version.toView()

        view.variables.size shouldBe 1
        view.contextRequirements shouldBe listOf(ContextRequirementView("conversation", emptyList(), emptyList()))
        view.extends shouldBe ExtendsRefView("templates/base", "2.0.0")
        view.output shouldBe OutputDeclarationView("JSON", "schemas/x")
    }

    @Test
    fun `PromptMetadata-toView は全フィールドを変換する`() {
        val metadata = PromptMetadata(PromptKey("support/faq-answer"), "FAQ", "cat", "desc", listOf("t"))

        val view = metadata.toView()

        view.key shouldBe "support/faq-answer"
        view.name shouldBe "FAQ"
        view.category shouldBe "cat"
        view.description shouldBe "desc"
        view.tags shouldBe listOf("t")
    }

    @Test
    fun `GetPromptResult-toView はmetadataがnullでも変換できる`() {
        val result = GetPromptResult(metadata = null, versions = listOf(promptVersion()))

        val view = result.toView()

        view.metadata shouldBe null
        view.versions.size shouldBe 1
    }

    @Test
    fun `GetPromptResult-toView はmetadataがあれば変換する`() {
        val metadata = PromptMetadata(PromptKey("support/faq-answer"), "FAQ")
        val result = GetPromptResult(metadata = metadata, versions = emptyList())

        result.toView().metadata?.name shouldBe "FAQ"
    }

    @Test
    fun `PromptSummary-toView は全フィールドを変換する`() {
        val summary =
            PromptSummary(
                key = PromptKey("support/faq-answer"),
                name = "FAQ",
                category = "cat",
                tags = listOf("t"),
                status = LifecycleState.Published,
                latestVersion = "1.1.0",
                publishedVersion = "1.0.0",
            )

        val view = summary.toView()

        view.key shouldBe "support/faq-answer"
        view.status shouldBe "Published"
        view.latestVersion shouldBe "1.1.0"
        view.publishedVersion shouldBe "1.0.0"
    }

    @Test
    fun `PromptVersionDiff-toView は全フィールドを変換する`() {
        val diff =
            PromptVersionDiff(
                key = PromptKey("support/faq-answer"),
                from = SemVer(1, 0, 0),
                to = SemVer(1, 1, 0),
                contentChanged = true,
                fromContentHash = "h1",
                toContentHash = "h2",
                variablesChanged = false,
                contextRequirementsChanged = false,
                extendsChanged = false,
                validationChanged = true,
                outputChanged = false,
            )

        val view = diff.toView()

        view.key shouldBe "support/faq-answer"
        view.from shouldBe "1.0.0"
        view.to shouldBe "1.1.0"
        view.contentChanged shouldBe true
        view.validationChanged shouldBe true
        view.outputChanged shouldBe false
    }
}
