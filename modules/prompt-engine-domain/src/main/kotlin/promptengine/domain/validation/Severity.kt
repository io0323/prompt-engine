package promptengine.domain.validation

/**
 * [Finding]・[ValidationRule]の重大度（設計書§2.10・§13.3）。
 */
enum class Severity {
    ERROR,
    WARNING,
    INFO,
}
