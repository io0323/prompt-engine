package promptengine.engine.render

import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.tokenizer.TokenizerPlugin
import java.security.MessageDigest
import java.text.Normalizer

/**
 * 未正規化の`messages`から一貫した[RenderedPrompt]（正規化済み`messages` + それに一致する
 * `tokenEstimate`・`renderHash`）を組み立てる唯一の入口（設計書§2.9、ADR-0013決定1）。
 *
 * `RenderEngineImpl`（AST展開経由の通常render）と、parseRepair修復ラウンドの合成
 * `RenderedPrompt`構築（`ExecutionCoordinator`、ADR-0014決定6）の両方から再利用するため、
 * `engine.render`パッケージのinternalユーティリティとして抽出した（P7、ADR-0014）。
 * `internal`のため`prompt-engine-core`モジュール外からは参照できない。
 *
 * 正規化（改行コード・行末空白・Unicode NFC、ADR-0013決定1）とハッシュ算出を[build]内に
 * 閉じ込め、正規化ロジック（`normalize`）とハッシュ算出ロジック（`compute`）は`private`とする。
 * かつて両者を別々の`public`関数として公開していたところ、呼出元が`normalize`を呼び忘れたまま
 * `compute`だけを呼べてしまい、正規化バイパスが実際に発生した（CodeRabbitレビュー指摘）。
 * 新しい呼出元がこの構造を壊さない限り、未正規化の`content`が`renderHash`の入力になったり、
 * 返却される`RenderedPrompt.messages`と実際にハッシュ化された内容が食い違ったりする経路は
 * 存在しない（`messages`と`renderHash`を[build]が単一の呼出内で同時に確定させるため）。
 */
internal object RenderHashCalculator {
    /** [RenderHashCalculator]自体のバージョン（renderHashに混入する、設計書§2.9「EngineVersion」）。 */
    const val ENGINE_VERSION = "1"

    private const val CRLF = "\r\n"
    private const val CR = "\r"
    private const val BYTE_3_SHIFT = 24
    private const val BYTE_2_SHIFT = 16
    private const val BYTE_1_SHIFT = 8

    /**
     * [messages]（未正規化でよい）を正規化した上で、[tokenizerPlugin]による`tokenEstimate`と
     * それに一致する`renderHash`を算出し、[RenderedPrompt]として返す。
     */
    fun build(
        messages: List<RenderedMessage>,
        outputFormat: OutputFormat,
        engineId: String,
        tokenizerPlugin: TokenizerPlugin,
        engineVersion: String = ENGINE_VERSION,
    ): RenderedPrompt {
        val normalized = messages.map { RenderedMessage(it.role, normalize(it.content)) }
        val tokenEstimate = tokenizerPlugin.estimate(normalized.joinToString(separator = "") { it.content })
        val renderHash = compute(normalized, outputFormat, engineId, engineVersion)
        return RenderedPrompt(normalized, outputFormat, tokenEstimate, renderHash)
    }

    /**
     * 改行コード・行末空白・Unicode正規化形（ADR-0013決定1）を[content]に適用する。
     */
    private fun normalize(content: String): String {
        val lineEndingsNormalized = content.replace(CRLF, "\n").replace(CR, "\n")
        val trailingWhitespaceStripped =
            lineEndingsNormalized.lineSequence().joinToString(separator = "\n") { it.trimEnd(' ', '\t') }
        return Normalizer.normalize(trailingWhitespaceStripped, Normalizer.Form.NFC)
    }

    /**
     * 各フィールドをUTF-8バイト長（4バイトbig-endian）+ 本体バイト列で区切る（長さプレフィックス方式）。
     * 単純な区切り文字（空白等）だと、区切り文字自身が`content`の内部に出現しうるため、
     * 構造の異なるmessages列がバイト列として偶然一致する余地が残る。長さを明示することで
     * フィールド境界を曖昧性なく復元可能にし、この衝突経路を構造的に排除する（ADR-0013決定1）。
     */
    private fun compute(
        messages: List<RenderedMessage>,
        outputFormat: OutputFormat,
        engineId: String,
        engineVersion: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.forEach { message ->
            digest.updateField(message.role.name)
            digest.updateField(message.content)
        }
        digest.updateField(outputFormat.name)
        digest.updateField(engineId)
        digest.updateField(engineVersion)
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
}
