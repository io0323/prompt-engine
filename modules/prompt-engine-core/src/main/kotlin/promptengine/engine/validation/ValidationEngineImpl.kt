package promptengine.engine.validation

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.validation.ValidationEngine
import promptengine.domain.validation.ValidationReport
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.BindingSet

/**
 * [ValidationEngine]の実装（設計書§2.10・§5.5、ADR-0012決定1）。
 *
 * 登録済み全[ValidationRule]を常に一様に呼び、Findingを集約する（1件目で止めない、
 * 設計書§5.5「全Rule実行→Report集約」）。モードやbindingsの状態による分岐は一切持たない
 * ── 各Ruleが自身の前提を自己防御的に判定する（ADR-0012決定4）。[rules]には標準5種に加え
 * `PolicyValidationRule`（`plugins/validator-policy`）もDI側（`prompt-engine-bootstrap`）が
 * 束ねて渡す想定。
 */
class ValidationEngineImpl(private val rules: List<ValidationRule>) : ValidationEngine {
    override fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): ValidationReport = ValidationReport(rules.flatMap { it.validate(compiled, variableBindings, contextBindings) })
}
