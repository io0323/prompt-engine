package promptengine.domain.validation

/**
 * DSL `validation.placeholders`（設計書§15.7）。`STRICT`は未束縛/未使用プレースホルダを
 * 共に`ERROR`、`LENIENT`は`WARNING`として報告する（`PlaceholderValidationRule`）。
 */
enum class PlaceholderMode {
    STRICT,
    LENIENT,
}
