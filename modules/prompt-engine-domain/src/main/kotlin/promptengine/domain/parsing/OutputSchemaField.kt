package promptengine.domain.parsing

/**
 * [OutputSchema]が宣言する1フィールドの検証条件（ADR-0014決定2）。
 */
data class OutputSchemaField(
    val name: String,
    val type: OutputFieldType,
    val required: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }
}
