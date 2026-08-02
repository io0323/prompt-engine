package promptengine.domain.validation

/**
 * [ValidationReport.hasErrors]がtrueの場合にPipeline Stage 6（Validation）が投げる
 * （設計書§13.3 `VALIDATION_FAILED`、ADR-0015決定4）。
 *
 * [ValidationEngine.validate]自体は例外を投げない設計（ADR-0012決定1）のため、
 * この判定と例外化はPipeline側（`ValidationStage`、`prompt-engine-application`）の責務。
 */
class ValidationFailedException(val report: ValidationReport) :
    RuntimeException("VALIDATION_FAILED: ${report.findings.count { it.severity == Severity.ERROR }} error finding(s)")
