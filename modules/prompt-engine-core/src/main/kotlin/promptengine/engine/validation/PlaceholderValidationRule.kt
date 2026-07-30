package promptengine.engine.validation

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.validation.Finding
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.BindingSet

/**
 * 未束縛プレースホルダ・未使用変数の検出（設計書§2.10）に加え、未宣言Contextスコープ
 * 参照の検出（設計書§2.7「未宣言スコープへの参照はValidationエラー」）も担う
 * （設計書§2.10の6 Ruleにはスコープ専用のRuleが無いため、"未束縛"の概念上最も近い
 * 本Ruleに統合する）。判定はAST（[CompiledPrompt.body]）と宣言
 * （[CompiledPrompt.variables]/[CompiledPrompt.contextRequirements]）だけを見るため、
 * 実際の束縛値（[variableBindings]/[contextBindings]）が空のCompile-onlyモードでも
 * 変わらず機能する（ADR-0012決定4）。
 *
 * - 未宣言変数の参照（プレースホルダ）・未使用の宣言変数: `validation.placeholders`
 *   （[PlaceholderMode]）に応じて`STRICT`はERROR、`LENIENT`はWARNING（ADR-0012決定3）。
 * - 未宣言Contextスコープの参照: 常にERROR固定（strict/lenientの対象外、設計書§2.7）。
 */
class PlaceholderValidationRule : ValidationRule {
    override fun id(): String = RULE_ID

    override fun severity(): Severity = Severity.ERROR

    override fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<Finding> {
        val refs = PropertyRefCollector.collect(compiled.body)
        val declaredVariableNames = compiled.variables.map { it.name }.toSet()
        val declaredContextScopes = compiled.contextRequirements.map { it.scope }.toSet()
        val placeholderSeverity =
            when (compiled.validation.placeholders) {
                PlaceholderMode.STRICT -> Severity.ERROR
                PlaceholderMode.LENIENT -> Severity.WARNING
            }

        val referencedVariableNames = mutableSetOf<String>()
        val findings = mutableListOf<Finding>()

        for (ref in refs) {
            val head = ref.path.first()
            if (head == CONTEXT_SEGMENT) {
                val scope = ref.path.getOrNull(1)
                if (scope != null && scope !in declaredContextScopes) {
                    findings +=
                        Finding(
                            ruleId = id(),
                            path = "$.context.$scope",
                            severity = Severity.ERROR,
                            message = "reference to undeclared context scope: $scope",
                        )
                }
            } else {
                referencedVariableNames += head
                if (head !in declaredVariableNames) {
                    findings +=
                        Finding(
                            ruleId = id(),
                            path = "$.parameters.$head",
                            severity = placeholderSeverity,
                            message = "unbound placeholder: '$head' is not declared as a variable",
                        )
                }
            }
        }

        (declaredVariableNames - referencedVariableNames).forEach { name ->
            findings +=
                Finding(
                    ruleId = id(),
                    path = "$.variables.$name",
                    severity = placeholderSeverity,
                    message = "declared variable is never referenced: '$name'",
                )
        }

        return findings
    }

    companion object {
        const val RULE_ID = "PlaceholderValidation"
        private const val CONTEXT_SEGMENT = "context"
    }
}
