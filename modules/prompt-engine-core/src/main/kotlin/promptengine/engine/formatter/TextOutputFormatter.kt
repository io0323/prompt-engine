package promptengine.engine.formatter

import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.render.OutputFormat

/**
 * `OutputFormat.TEXT`の既定[OutputFormatter]（実装ガイド§6.8、`prompt-engine-core`内蔵）。
 *
 * プレーンテキストは構造化フォーマット指示・パース検証の対象外のため、[instruction]は常に
 * 空文字を返し（`RenderEngineImpl`はこの場合`messages`への注入自体を行わない）、[parse]は
 * 常に成功し[raw]をそのまま保持する。
 */
class TextOutputFormatter : OutputFormatter {
    override fun format(): OutputFormat = OutputFormat.TEXT

    override fun instruction(schema: OutputSchema?): String = ""

    override fun parse(
        raw: String,
        schema: OutputSchema?,
    ): ParsedOutput = ParsedOutput(OutputFormat.TEXT, raw = raw)
}
