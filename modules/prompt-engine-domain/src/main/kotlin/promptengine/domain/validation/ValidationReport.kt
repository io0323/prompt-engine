package promptengine.domain.validation

/**
 * [ValidationEngine.validate]の集約結果（設計書§2.10・§5.5）。
 *
 * 全[ValidationRule]を実行した後のFinding一覧をそのまま保持する（1件目で止めない、
 * 設計書§5.3/§5.5「全Rule実行→Report集約」）。[hasErrors]が`true`なら
 * `VALIDATION_FAILED`として扱うかどうかは呼出元（P8 Pipeline Orchestrator）の責務であり、
 * このクラス自身は例外を投げない（ADR-0012決定1）。
 */
data class ValidationReport(val findings: List<Finding>) {
    val hasErrors: Boolean get() = findings.any { it.severity == Severity.ERROR }

    companion object {
        /** Findingを1件も持たない空の[ValidationReport]を返す。 */
        fun empty(): ValidationReport = ValidationReport(emptyList())
    }
}
