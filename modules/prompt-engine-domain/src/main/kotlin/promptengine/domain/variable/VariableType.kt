package promptengine.domain.variable

/**
 * DSL変数の型（設計書§4.4 VariableDefinition）。
 */
enum class VariableType {
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT,
}
