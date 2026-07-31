package promptengine.engine.render

import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import java.security.MessageDigest
import java.text.Normalizer

/**
 * [promptengine.domain.render.RenderedPrompt.renderHash]の算出ロジックと、その入力となる
 * `content`の正規化規則（設計書§2.9、ADR-0013決定1）。
 *
 * `RenderEngineImpl`（AST展開経由の通常render）と、parseRepair修復ラウンドの合成
 * `RenderedPrompt`構築（`ExecutionCoordinator`、ADR-0014決定6）の両方から再利用するため、
 * `engine.render`パッケージのinternalユーティリティとして抽出した（P7、ADR-0014）。
 * `internal`のため`prompt-engine-core`モジュール外からは参照できない。両呼出元とも、
 * [normalize]を適用した後の`content`を[compute]に渡すこと（CodeRabbitレビュー指摘: 正規化前の
 * `content`をハッシュ化すると、等価なはずのmessages列が異なる`renderHash`になりうる）。
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
     * 改行コード・行末空白・Unicode正規化形（ADR-0013決定1）を[content]に適用する。
     */
    fun normalize(content: String): String {
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
    fun compute(
        messages: List<RenderedMessage>,
        outputFormat: OutputFormat,
        engineId: String,
        engineVersion: String = ENGINE_VERSION,
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
