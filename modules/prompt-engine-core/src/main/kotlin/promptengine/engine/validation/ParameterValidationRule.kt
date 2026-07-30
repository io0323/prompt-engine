package promptengine.engine.validation

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.BindingSet

/**
 * 型・制約（`pattern`/`min`/`max`/`enum`/`maxLength`）検証（設計書§2.10）。
 *
 * [promptengine.domain.variable.VariableDefinition.constraints]は`<key>:<value>`形式の
 * 文字列（ADR-0012決定6、設計書§15.2）。未知のキーは無視する（前方互換）。
 * [variableBindings]に値が存在する変数のみを対象とする（ADR-0012決定4と同じ理由で
 * Compile-onlyでも自然に空リストを返す）。
 *
 * 制約値自体が不正（`min:notanumber`・不正な正規表現等）の場合も例外を投げず、
 * その旨のFindingを返す。`ValidationEngineImpl`はRule単位の例外隔離を持たないため
 * （ADR-0012決定4は前提データ欠如の扱いのみを定める）、1件の不正な制約が
 * Report全体を落とすことを避ける。
 */
class ParameterValidationRule : ValidationRule {
    override fun id(): String = RULE_ID

    override fun severity(): Severity = Severity.ERROR

    override fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<Finding> =
        compiled.variables.flatMap { variable ->
            val value = variableBindings[variable.name]
            if (value == null || variable.constraints.isEmpty()) {
                emptyList()
            } else {
                variable.constraints.mapNotNull { constraint ->
                    checkConstraint(value, constraint)?.let { message ->
                        Finding(
                            ruleId = id(),
                            path = "$.parameters.${variable.name}",
                            severity = severity(),
                            message = "$message: '${variable.name}'",
                        )
                    }
                }
            }
        }

    private fun checkConstraint(
        value: Any,
        constraint: String,
    ): String? {
        val separatorIndex = constraint.indexOf(':')
        if (separatorIndex < 0) return null
        val key = constraint.substring(0, separatorIndex).trim()
        val rawValue = constraint.substring(separatorIndex + 1).trim()
        return when (key) {
            "pattern" -> checkPattern(value, rawValue)
            "min" -> checkMin(value, rawValue)
            "max" -> checkMax(value, rawValue)
            "enum" -> checkEnum(value, rawValue)
            "maxLength" -> checkMaxLength(value, rawValue)
            else -> null
        }
    }

    private fun checkPattern(
        value: Any,
        pattern: String,
    ): String? {
        val text = value as? String
        val regex = runCatching { Regex(pattern) }.getOrNull()
        return when {
            text == null -> null
            regex == null -> "invalid pattern constraint '$pattern'"
            !regex.matches(text) -> "value does not match pattern '$pattern'"
            else -> null
        }
    }

    private fun checkMin(
        value: Any,
        rawMin: String,
    ): String? {
        val number = (value as? Number)?.toDouble()
        val min = rawMin.toDoubleOrNull()
        return when {
            number == null -> null
            min == null -> "invalid min constraint value '$rawMin'"
            number < min -> "value $number is below min $min"
            else -> null
        }
    }

    private fun checkMax(
        value: Any,
        rawMax: String,
    ): String? {
        val number = (value as? Number)?.toDouble()
        val max = rawMax.toDoubleOrNull()
        return when {
            number == null -> null
            max == null -> "invalid max constraint value '$rawMax'"
            number > max -> "value $number exceeds max $max"
            else -> null
        }
    }

    private fun checkEnum(
        value: Any,
        rawValues: String,
    ): String? {
        val allowed = rawValues.split(",").map { it.trim() }
        return if (value.toString() in allowed) null else "value '$value' is not one of $allowed"
    }

    private fun checkMaxLength(
        value: Any,
        rawMaxLength: String,
    ): String? {
        val text = value as? String
        val maxLength = rawMaxLength.toIntOrNull()
        return when {
            text == null -> null
            maxLength == null -> "invalid maxLength constraint value '$rawMaxLength'"
            text.length > maxLength -> "value length ${text.length} exceeds maxLength $maxLength"
            else -> null
        }
    }

    companion object {
        const val RULE_ID = "ParameterValidation"
    }
}
