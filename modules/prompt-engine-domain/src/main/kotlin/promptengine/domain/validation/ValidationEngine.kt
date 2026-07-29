package promptengine.domain.validation

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.variable.BindingSet

/**
 * Validationの入口（設計書§2.6ステージ6・§5.5シーケンス）。
 *
 * `prompt-engine-application`（Pipeline Orchestrator、P8）はこのInterfaceだけを参照し、
 * `prompt-engine-core`の実装（`ValidationEngineImpl`、登録済み全[ValidationRule]の実行）には
 * 依存しない（[promptengine.domain.composition.CompositionService]と同じ形、ADR-0012決定1）。
 *
 * [validate] は例外を投げない。ERROR severityの[Finding]が1件でもあれば
 * `VALIDATION_FAILED`として扱うかどうかは呼出元（P8）の責務（[ValidationReport.hasErrors]
 * 参照、ADR-0012決定1。§5.5シーケンス図の`alt ERROR含む`分岐もPO側の分岐として描かれている）。
 */
interface ValidationEngine {
    fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): ValidationReport
}
