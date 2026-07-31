package promptengine.domain.parsing

import promptengine.domain.render.OutputFormat

/**
 * Response Parsingの入口（設計書§3.4疑似コード、§2.6ステージ10・§5.7・§5.9シーケンス、
 * §16拡張ポイント#7）。
 */
interface OutputFormatter {
    /** この[OutputFormatter]が対応する[OutputFormat]。 */
    fun format(): OutputFormat

    /**
     * Render時に`messages`へ注入するフォーマット指示文を生成する（§5.7シーケンス
     * `RE -> OF: instruction(outputSchema)`）。指示文が不要なフォーマット（[format]=TEXT等）は
     * 空文字を返す（呼出元はその場合messagesへの注入自体を行わない、ADR-0014決定5）。
     */
    fun instruction(schema: OutputSchema?): String

    /**
     * [raw]を[schema]に基づき解析する。失敗時は[ParseFailedException]を投げる。
     *
     * [ParseFailedException.reason]は構造的な理由のみを設定すること（[ParseFailedException]の
     * KDoc参照）。
     */
    fun parse(
        raw: String,
        schema: OutputSchema?,
    ): ParsedOutput
}
