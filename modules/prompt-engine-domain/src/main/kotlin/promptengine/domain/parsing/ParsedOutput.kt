package promptengine.domain.parsing

import promptengine.domain.render.OutputFormat

/**
 * [OutputFormatter.parse]の成功結果（設計書§2.6ステージ10、ADR-0014決定2）。
 *
 * [fields]は構造化出力（JSON等）のトップレベルフィールドをKotlin標準型（文字列・数値・真偽値・
 * `List<*>`・`Map<String, *>`・`null`）に変換した結果。TEXT/MARKDOWN等、構造を持たない
 * フォーマットでは空のままでよく、[raw]に元の応答をそのまま保持する。
 */
@ConsistentCopyVisibility
data class ParsedOutput private constructor(
    val format: OutputFormat,
    val fields: Map<String, Any?>,
    val raw: String,
) {
    companion object {
        /** [fields]を不変コピー（[Map.toMap]）してから保持する。 */
        operator fun invoke(
            format: OutputFormat,
            fields: Map<String, Any?> = emptyMap(),
            raw: String,
        ): ParsedOutput = ParsedOutput(format, fields.toMap(), raw)
    }
}
