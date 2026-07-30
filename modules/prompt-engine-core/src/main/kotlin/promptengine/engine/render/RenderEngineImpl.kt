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

    /**
     * 各フィールドをUTF-8バイト長（4バイトbig-endian）+ 本体バイト列で区切る（長さプレフィックス方式）。
     * 単純な区切り文字（空白等）だと、区切り文字自身が`content`の内部に出現しうるため、
     * 構造の異なるmessages列がバイト列として偶然一致する余地が残る
     * （例: role="SYSTEM"のメッセージ1件とrole部分に相当するバイト列が`content`の先頭に
     * 混入した別構成のメッセージ列が同じバイト列になりうる）。長さを明示することで
     * フィールド境界を曖昧性なく復元可能にし、この衝突経路を構造的に排除する。
     */
    private fun computeRenderHash(
        messages: List<RenderedMessage>,
        outputFormat: OutputFormat,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.forEach { message ->
            digest.updateField(message.role.name)
            digest.updateField(message.content)
        }
        digest.updateField(outputFormat.name)
        digest.updateField(templateEngine.id())
        digest.updateField(ENGINE_VERSION)
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun MessageDigest.updateField(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(lengthPrefix(bytes.size))
        update(bytes)
    }

    private fun lengthPrefix(length: Int): ByteArray =
        byteArrayOf(
            (length ushr BYTE_3_SHIFT).toByte(),
            (length ushr BYTE_2_SHIFT).toByte(),
            (length ushr BYTE_1_SHIFT).toByte(),
            length.toByte(),
        )

    companion object {
        /** [RenderEngineImpl]自体のバージョン（renderHashに混入する、設計書§2.9「EngineVersion」）。 */
        const val ENGINE_VERSION = "1"
        private const val CRLF = "\r\n"
        private const val CR = "\r"

        // Int(4バイト)をbig-endianで書き出す際の各バイトへのビットシフト量。
        private const val BYTE_3_SHIFT = 24
        private const val BYTE_2_SHIFT = 16
        private const val BYTE_1_SHIFT = 8
    }
}
