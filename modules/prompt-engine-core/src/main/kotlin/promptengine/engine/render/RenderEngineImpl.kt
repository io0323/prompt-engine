package promptengine.engine.render

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.parsing.OutputSchema
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderEngine
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.render.TemplateEngine
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.BindingSet

/**
 * Renderingの入口実装（設計書§5.7シーケンス、ADR-0013決定1・決定10、ADR-0014決定5）。
 *
 * [templateEngine]経由でのみASTを展開し、[outputFormatters]経由の`OutputFormatter.instruction()`
 * 注入（ADR-0014決定5）まで終えた最終的な`messages`に対して[RenderHashCalculator.normalize]
 * （ADR-0013決定1の正規化規則: 改行コード・行末空白・Unicode NFC）を適用してから
 * [RenderHashCalculator.compute]でハッシュ算出する。注入前に正規化すると、注入される
 * instruction文字列自体が正規化規則の対象外になってしまう（CodeRabbitレビュー指摘）ため、
 * 正規化はinstruction注入後の最終messagesに対して一括で行う。
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
                ?: error("no OutputFormatter registered for outputFormat=$outputFormat")
        val injected = injectInstruction(expanded, formatter.instruction(outputSchema))
        val messages = injected.map { RenderedMessage(it.role, RenderHashCalculator.normalize(it.content)) }

        val tokenEstimate = tokenizerPlugin.estimate(messages.joinToString(separator = "") { it.content })
        val renderHash = RenderHashCalculator.compute(messages, outputFormat, templateEngine.id())

        return RenderedPrompt(messages, outputFormat, tokenEstimate, renderHash)
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
