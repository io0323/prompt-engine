package promptengine.domain.variable

/**
 * DSL変数の定義（設計書§4.4）。
 */
data class VariableDefinition(
    val name: String,
    val type: VariableType,
    val required: Boolean = false,
    val default: Any? = null,
    val constraints: List<String> = emptyList(),
    val sensitive: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }
}
