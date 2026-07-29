package promptengine.domain.validation

/**
 * 1件のRule違反（設計書§13.3 `details[]`）。
 *
 * [path] はJSONPath風の自由記述文字列（例: `"$.parameters.productName"`）。
 * [severity] はFinding自身が持つ実際の重大度であり、[ValidationRule.severity]
 * （そのRuleが通常報告する既定値）と必ずしも一致しない
 * （例: `PlaceholderValidationRule`は`validation.placeholders`のstrict/lenientに応じて
 * Findingごとに異なるseverityを計算する、ADR-0012決定3）。
 */
data class Finding(
    val ruleId: String,
    val path: String,
    val severity: Severity,
    val message: String,
)
