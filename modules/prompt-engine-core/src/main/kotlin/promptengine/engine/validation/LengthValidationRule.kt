package promptengine.engine.validation

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.BindingSet

/**
 * 文字数・推定Token数の上限検証（設計書§2.10）。Render（P6/P8）より前段階のため、
 * [AstTextEstimator]によるASTベストエフォート推定を使う（ADR-0012決定5）。
 * `validation.maxLength`/`maxTokens`いずれも未宣言なら検証対象なし（空リスト）。
 */
class LengthValidationRule(private val tokenizerPlugin: TokenizerPlugin) : ValidationRule {
    override fun id(): String = RULE_ID

    override fun severity(): Severity = Severity.ERROR

    override fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<Finding> {
        val maxLength = compiled.validation.maxLength
        val maxTokens = compiled.validation.maxTokens
        if (maxLength == null && maxTokens == null) return emptyList()

        val text = AstTextEstimator.estimate(compiled.body, variableBindings, contextBindings)
        val findings = mutableListOf<Finding>()

        if (maxLength != null && text.length > maxLength) {
            findings +=
                Finding(
                    ruleId = id(),
                    path = "$.body",
                    severity = severity(),
                    message = "estimated length ${text.length} exceeds maxLength $maxLength",
                )
        }
        if (maxTokens != null) {
            val tokenCount = tokenizerPlugin.estimate(text).value
            if (tokenCount > maxTokens) {
                findings +=
                    Finding(
                        ruleId = id(),
                        path = "$.body",
                        severity = severity(),
                        message = "estimated tokens $tokenCount exceeds maxTokens $maxTokens",
                    )
            }
        }
        return findings
    }

    companion object {
        const val RULE_ID = "LengthValidation"
    }
}
