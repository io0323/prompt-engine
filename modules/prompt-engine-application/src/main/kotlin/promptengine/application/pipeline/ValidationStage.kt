package promptengine.application.pipeline

import promptengine.domain.context.ContextBindingSet
import promptengine.domain.observability.MetricsRecorder
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.validation.ValidationEngine
import promptengine.domain.validation.ValidationFailedException
import promptengine.domain.variable.BindingSet

/**
 * Stage 6（Validation、設計書§2.6）。[ValidationEngine]へ委譲する。
 *
 * [ValidationEngine.validate]自体は例外を投げない（ADR-0012決定1）ため、このStageが
 * `ValidationReport.hasErrors`を見て[ValidationFailedException]を投げるかどうかを
 * 判定する（ADR-0015決定4）。
 *
 * `validation_failure_count`（設計書§2.15）は[ValidationReport.findings]を直接持つこの
 * Stageでのみ記録できる（`ruleId`/`severity`は[Finding][promptengine.domain.validation.Finding]の
 * フィールドであり、`PipelineOrchestrator`からは見えない）。
 */
class ValidationStage(
    private val validationEngine: ValidationEngine,
    private val metricsRecorder: MetricsRecorder,
) : PipelineStage {
    override val name: String = "Validation"

    override fun execute(context: PipelineContext): PipelineContext {
        val compiled =
            checkNotNull(context.compiled) { "ValidationStage requires compiled (Stage 2 Merge must run first)" }
        val report =
            validationEngine.validate(
                compiled,
                context.variableBindings ?: BindingSet.empty(),
                context.contextBindings ?: ContextBindingSet.empty(),
            )
        report.findings.forEach { finding ->
            metricsRecorder.incrementValidationIssue(finding.ruleId, finding.severity)
        }
        if (report.hasErrors) {
            throw ValidationFailedException(report)
        }
        return context.copy(validationReport = report)
    }
}
