package promptengine.engine.render

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderEngine
import promptengine.domain.render.RenderFailedException
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.render.TemplateEngine
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.BindingSet

/**
 * Renderingの入口実装（設計書§5.7シーケンス、ADR-0013決定1・決定10、ADR-0014決定5）。
 *
 * [templateEngine]経由でのみASTを展開し、[outputFormatters]経由の`OutputFormatter.instruction()`
 * 注入（ADR-0014決定5）まで終えた最終的な`messages`を[RenderHashCalculator.build]へ渡す。
 * 正規化（ADR-0013決定1の正規化規則: 改行コード・行末空白・Unicode NFC）とハッシュ算出は
 * [RenderHashCalculator.build]内で不可分に行われるため、ここでは未正規化の`messages`を
 * 組み立てるだけでよい（正規化の呼び忘れが構造的に起こらない、[RenderHashCalculator]のKDoc参照）。
 */
class RenderEngineImpl(
    private val templateEngine: TemplateEngine,
    private val tokenizerPlugin: TokenizerPlugin,
    private val outputFormatters: Map<OutputFormat, OutputFormatter>,
) : RenderEngine {
    override fun render(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        outputFormat: OutputFormat,
        outputSchema: OutputSchema?,
    ): RenderedPrompt {
        val expanded = templateEngine.expand(compiled.body, variableBindings, contextBindings)

        val formatter =
            outputFormatters[outputFormat]
                ?: throw RenderFailedException("no OutputFormatter registered for outputFormat=$outputFormat")
        val messages = injectInstruction(expanded, formatter.instruction(outputSchema))

        return RenderHashCalculator.build(messages, outputFormat, templateEngine.id(), tokenizerPlugin)
    }

    /**
     * [instruction]が空文字なら何もしない（`TextOutputFormatter`は常に空文字を返し、無意味な
     * 空行や`renderHash`の変化を避ける）。空文字でなければ、最初の`role == SYSTEM`のメッセージが
     * あればその`content`末尾に改行区切りで追記し、無ければ新規のSYSTEMメッセージとして
     * [messages]末尾に追加する（ADR-0014決定5）。
     */
    private fun injectInstruction(
        messages: List<RenderedMessage>,
        instruction: String,
    ): List<RenderedMessage> {
        if (instruction.isBlank()) return messages
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.mapIndexed { index, message ->
                if (index == systemIndex) {
                    RenderedMessage(message.role, "${message.content}\n$instruction")
                } else {
                    message
                }
            }
        } else {
            messages + RenderedMessage(MessageRole.SYSTEM, instruction)
        }
    }
}
