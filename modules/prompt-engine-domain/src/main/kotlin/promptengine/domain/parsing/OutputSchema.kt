package promptengine.domain.parsing

/**
 * Structured Output用のSchema（設計書§15.1 `output.schemaRef`が指す実体、ADR-0014決定2）。
 *
 * JSON Schema全体を表現しない。トップレベルの必須フィールドと型のみを検証する最小限の
 * 構造的サブセットとする（`VariableDefinition.constraints`が`pattern:<regex>`等の限定的な
 * 文字列表現に留めているのと同じ考え方）。ネストしたオブジェクト・配列要素の型検証は対象外。
 *
 * M1では`schemaRef`からの自動解決経路が無く、呼出側が明示的に構築して渡す値である
 * （[Issue #32](https://github.com/io0323/prompt-engine/issues/32)でDSLからの回収を追跡）。
 */
@ConsistentCopyVisibility
data class OutputSchema private constructor(
    val id: String,
    val fields: List<OutputSchemaField>,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
    }

    companion object {
        /** [fields]を不変コピー（[List.toList]）してから保持する。 */
        operator fun invoke(
            id: String,
            fields: List<OutputSchemaField> = emptyList(),
        ): OutputSchema = OutputSchema(id, fields.toList())
    }
}
