package promptengine.engine.render

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderEngine
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.render.TemplateEngine
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.BindingSet
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Renderingの入口実装（設計書§5.7シーケンス、ADR-0013決定1・決定10）。
 *
 * [templateEngine]経由でのみASTを展開し、[renderHash]算出前に決定1の正規化規則
 * （改行コード・行末空白・Unicode NFC）を適用する。`OutputFormatter.instruction()`による
 * フォーマット指示文の注入はP7スコープのため、[outputFormat]は素通しするのみ。
 */
class RenderEngineImpl(
    private val templateEngine: TemplateEngine,
    private val tokenizerPlugin: TokenizerPlugin,
) : RenderEngine {
    override fun render(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
        outputFormat: OutputFormat,
    ): RenderedPrompt {
        val messages =
            templateEngine
                .expand(compiled.body, variableBindings, contextBindings)
                .map { RenderedMessage(it.role, normalize(it.content)) }

        val tokenEstimate = tokenizerPlugin.estimate(messages.joinToString(separator = "") { it.content })
        val renderHash = computeRenderHash(messages, outputFormat)

        return RenderedPrompt(messages, outputFormat, tokenEstimate, renderHash)
    }

    private fun normalize(content: String): String {
        val lineEndingsNormalized = content.replace(CRLF, "\n").replace(CR, "\n")
        val trailingWhitespaceStripped =
            lineEndingsNormalized.lineSequence().joinToString(separator = "\n") { it.trimEnd(' ', '\t') }
        return Normalizer.normalize(trailingWhitespaceStripped, Normalizer.Form.NFC)
    }

    private fun computeRenderHash(
        messages: List<RenderedMessage>,
        outputFormat: OutputFormat,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.forEach { message ->
            digest.update(message.role.name.toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
            digest.update(message.content.toByteArray(Charsets.UTF_8))
            digest.update(SEPARATOR)
        }
        digest.update(outputFormat.name.toByteArray(Charsets.UTF_8))
        digest.update(SEPARATOR)
        digest.update(templateEngine.id().toByteArray(Charsets.UTF_8))
        digest.update(SEPARATOR)
        digest.update(ENGINE_VERSION.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        /** [RenderEngineImpl]自体のバージョン（renderHashに混入する、設計書§2.9「EngineVersion」）。 */
        const val ENGINE_VERSION = "1"
        private val SEPARATOR = byteArrayOf(0x20)
        private const val CRLF = "\r\n"
        private const val CR = "\r"
    }
}
