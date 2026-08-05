package promptengine.application.view

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.SemVer
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType

class DomainValueFactoryTest {
    @Test
    fun `promptKey は正規のnamespace-slash-name文字列からPromptKeyを構築する`() {
        DomainValueFactory.promptKey("support/faq-answer").value shouldBe "support/faq-answer"
    }

    @Test
    fun `promptKey は不正な形式でIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { DomainValueFactory.promptKey("no-slash") }
    }

    @Test
    fun `promptKeyText はnamespaceとnameを結合する`() {
        DomainValueFactory.promptKeyText("support", "faq-answer") shouldBe "support/faq-answer"
    }

    @Test
    fun `promptKeyText はnamespaceが不正な形式ならIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { DomainValueFactory.promptKeyText("Support", "faq-answer") }
    }

    @Test
    fun `promptKeyText はnameが空文字ならIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { DomainValueFactory.promptKeyText("support", "") }
    }

    @Test
    fun `semVer は major-minor-patch形式を解釈する`() {
        DomainValueFactory.semVer("1.2.3") shouldBe SemVer(1, 2, 3)
    }

    @Test
    fun `semVer はセグメント数が3でなければIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { DomainValueFactory.semVer("1.2") }
        shouldThrow<IllegalArgumentException> { DomainValueFactory.semVer("1.2.3.4") }
    }

    @Test
    fun `semVer は数値でないセグメントがあればNumberFormatException`() {
        shouldThrow<NumberFormatException> { DomainValueFactory.semVer("1.x.3") }
    }

    @Test
    fun `versionRef は latest(大文字小文字問わず)をVersionRef-Latestにする`() {
        DomainValueFactory.versionRef("latest") shouldBe VersionRef.Latest
        DomainValueFactory.versionRef("LATEST") shouldBe VersionRef.Latest
    }

    @Test
    fun `versionRef はSemVer形式をVersionRef-Fixedにする`() {
        DomainValueFactory.versionRef("1.2.3") shouldBe VersionRef.Fixed(SemVer(1, 2, 3))
    }

    @Test
    fun `versionRef はそれ以外をVersionRef-Aliasにする`() {
        DomainValueFactory.versionRef("stable") shouldBe VersionRef.Alias("stable")
    }

    @Test
    fun `lifecycleState は設計書の6状態文字列を解釈する`() {
        DomainValueFactory.lifecycleState("Draft") shouldBe LifecycleState.Draft
        DomainValueFactory.lifecycleState("InReview") shouldBe LifecycleState.InReview
        DomainValueFactory.lifecycleState("Approved") shouldBe LifecycleState.Approved
        DomainValueFactory.lifecycleState("Published") shouldBe LifecycleState.Published
        DomainValueFactory.lifecycleState("Deprecated") shouldBe LifecycleState.Deprecated
        DomainValueFactory.lifecycleState("Archived") shouldBe LifecycleState.Archived
    }

    @Test
    fun `lifecycleState は未知の文字列でIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { DomainValueFactory.lifecycleState("draft") }
    }

    @Test
    fun `variableDefinition はInputの全フィールドをdomain型へ変換する`() {
        val input =
            VariableDefinitionInput(
                name = "productName",
                type = "STRING",
                source = "RUNTIME",
                required = true,
                default = "X1",
                constraints = listOf("min:1"),
                sensitive = false,
            )

        val result = DomainValueFactory.variableDefinition(input)

        result.name shouldBe "productName"
        result.type shouldBe VariableType.STRING
        result.source shouldBe VariableSource.RUNTIME
        result.required shouldBe true
        result.default shouldBe "X1"
        result.constraints shouldBe listOf("min:1")
        result.sensitive shouldBe false
    }

    @Test
    fun `variableDefinition はsensitiveをdomain型へ変換する`() {
        val input = VariableDefinitionInput(name = "apiKey", type = "STRING", sensitive = true)

        val result = DomainValueFactory.variableDefinition(input)

        result.sensitive shouldBe true
        result.default shouldBe null
    }

    @Test
    fun `variableDefinition は未知のtypeでIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            DomainValueFactory.variableDefinition(VariableDefinitionInput(name = "x", type = "UNKNOWN"))
        }
    }

    @Test
    fun `contextRequirement はInputをそのままdomain型へ変換する`() {
        val input =
            ContextRequirementInput(scope = "conversation", required = listOf("messages"), optional = listOf("memory"))

        val result = DomainValueFactory.contextRequirement(input)

        result.scope shouldBe "conversation"
        result.required shouldBe listOf("messages")
        result.optional shouldBe listOf("memory")
    }

    @Test
    fun `validationSettings はnullなら既定値を返す`() {
        val result = DomainValueFactory.validationSettings(null)

        result.maxLength shouldBe null
        result.maxTokens shouldBe null
        result.policies shouldBe emptyList()
        result.placeholders shouldBe PlaceholderMode.LENIENT
    }

    @Test
    fun `validationSettings は非nullならInputを変換する`() {
        val input =
            ValidationSettingsInput(
                maxLength = 100,
                maxTokens = 50,
                policies = listOf("no-pii"),
                placeholders = "STRICT",
            )

        val result = DomainValueFactory.validationSettings(input)

        result.maxLength shouldBe 100
        result.maxTokens shouldBe 50
        result.policies shouldBe listOf("no-pii")
        result.placeholders shouldBe PlaceholderMode.STRICT
    }

    @Test
    fun `validationSettings は未知のplaceholdersでIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            DomainValueFactory.validationSettings(ValidationSettingsInput(placeholders = "UNKNOWN"))
        }
    }

    @Test
    fun `outputDeclaration はnullならnullを返す`() {
        DomainValueFactory.outputDeclaration(null) shouldBe null
    }

    @Test
    fun `outputDeclaration は非nullならInputを変換する`() {
        val result =
            DomainValueFactory.outputDeclaration(
                OutputDeclarationInput(format = "JSON", schemaRef = "schemas/x"),
            )

        result?.format shouldBe OutputFormat.JSON
        result?.schemaRef shouldBe "schemas/x"
    }

    @Test
    fun `outputDeclaration は未知のformatでIllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> {
            DomainValueFactory.outputDeclaration(OutputDeclarationInput(format = "UNKNOWN"))
        }
    }
}
