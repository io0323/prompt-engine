package promptengine.domain.parsing

/**
 * [OutputSchemaField.type]が取りうる値（設計書§15.2 Variable型一覧のうち構造化出力検証に
 * 使う値のサブセット、ADR-0014決定2）。
 */
enum class OutputFieldType {
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT,
}
