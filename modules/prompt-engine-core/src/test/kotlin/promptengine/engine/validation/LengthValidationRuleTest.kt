package promptengine.engine.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.TextNode
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.BindingSet

class LengthValidationRuleTest {
    private val tokenizer = TokenizerPlugin { text -> TokenCount(text.length) }
    private val rule = LengthValidationRule(tokenizer)

    private fun compiledPrompt(
        text: String,
        settings: ValidationSettings,
    ): CompiledPrompt =
        CompiledPrompt(
            body = listOf(TextNode(text)),
            dependencies = emptyList(),
            variables = emptyList(),
            contextRequirements = emptyList(),
            validation = settings,
        )

    @Test
    fun `maxLength maxTokensともに宣言が無ければ検証対象なし`() {
        val compiled = compiledPrompt("hello", ValidationSettings())

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `maxLength以内ならFindingを出さない`() {
        val compiled = compiledPrompt("hello", ValidationSettings(maxLength = 100))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }

    @Test
    fun `maxLengthを超過するとERRORのFindingを出す`() {
        val compiled = compiledPrompt("hello world", ValidationSettings(maxLength = 5))

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings.single().severity shouldBe Severity.ERROR
        findings.single().message shouldBe "estimated length 11 exceeds maxLength 5"
    }

    @Test
    fun `maxTokensを超過するとFindingを出す`() {
        val fakeTokenizer = TokenizerPlugin { TokenCount(1000) }
        val rule = LengthValidationRule(fakeTokenizer)
        val compiled = compiledPrompt("hello", ValidationSettings(maxTokens = 10))

        val findings = rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty())

        findings.single().message shouldBe "estimated tokens 1000 exceeds maxTokens 10"
    }

    @Test
    fun `maxTokens以内ならFindingを出さない`() {
        val fakeTokenizer = TokenizerPlugin { TokenCount(1) }
        val rule = LengthValidationRule(fakeTokenizer)
        val compiled = compiledPrompt("hello", ValidationSettings(maxTokens = 10))

        rule.validate(compiled, BindingSet.empty(), ContextBindingSet.empty()) shouldBe emptyList()
    }
}
