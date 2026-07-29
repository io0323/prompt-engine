package promptengine.engine.validation

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.BindingSet
import promptengine.domain.variable.VariableType

/**
 * 呼出パラメータをVariable Schema（[promptengine.domain.variable.VariableDefinition.type]）で
 * 検証する（設計書§2.10）。[variableBindings]に実際に値が存在する変数のみを対象とし、
 * 値が無い変数は検証対象外（ADR-0012決定4。Compile-onlyでは[variableBindings]が
 * 空になりうるため、自然に空リストを返す）。
 */
class SchemaValidationRule : ValidationRule {
    override fun id(): String = RULE_ID

    override fun severity(): Severity = Severity.ERROR

    override fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<Finding> =
        compiled.variables.mapNotNull { variable ->
            val value = variableBindings[variable.name] ?: return@mapNotNull null
            if (matchesType(value, variable.type)) {
                null
            } else {
                Finding(
                    ruleId = id(),
                    path = "$.parameters.${variable.name}",
                    severity = severity(),
                    message = "value does not match declared type ${variable.type} for variable '${variable.name}'",
                )
            }
        }

    private fun matchesType(
        value: Any,
        type: VariableType,
    ): Boolean =
        when (type) {
            VariableType.STRING -> value is String || value is SensitiveValue
            VariableType.NUMBER -> value is Number
            VariableType.BOOLEAN -> value is Boolean
            VariableType.ARRAY -> value is List<*>
            VariableType.OBJECT -> value is Map<*, *>
        }

    companion object {
        const val RULE_ID = "SchemaValidation"
    }
}
