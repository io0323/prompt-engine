package promptengine.domain.render

import promptengine.domain.shared.TokenCount

/**
 * Render Engineの出力（設計書§2.9・§4.4）。
 *
 * [modelHints]はM1では§4.4 VO一覧・実装ガイド§6.7のP6スコープ定義に含まれず持たなかったが
 * （ADR-0013決定6）、M2-4bでAPAP連携が具体化する契機（Benchmark Determinism計測に
 * `temperature`の実行時受け渡しが必要）に当たったため追加した（ADR-0035決定5）。
 *
 * [renderHash]は`SHA-256(normalize(messages) + engineId + engineVersion)`
 * （正規化規則・sensitive値の扱いはADR-0013決定1、[promptengine.domain.render.RenderEngine]参照）。
 * [modelHints]はRenderステップの出力バイトに影響しないため、意図的にハッシュ入力から
 * 除外する（[promptengine.engine.render.RenderHashCalculator]参照）。
 */
@ConsistentCopyVisibility
data class RenderedPrompt private constructor(
    val messages: List<RenderedMessage>,
    val outputFormat: OutputFormat,
    val tokenEstimate: TokenCount,
    val renderHash: String,
    val modelHints: ModelHints?,
) {
    init {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        require(renderHash.isNotBlank()) { "renderHash must not be blank" }
    }

    companion object {
        /**
         * [messages]を不変コピー（[List.toList]）してから保持する。呼出元が渡した
         * `MutableList`を構築後に変更しても、このインスタンスの[messages]・[renderHash]は
         * 影響を受けない。
         */
        operator fun invoke(
            messages: List<RenderedMessage>,
            outputFormat: OutputFormat,
            tokenEstimate: TokenCount,
            renderHash: String,
            modelHints: ModelHints? = null,
        ): RenderedPrompt = RenderedPrompt(messages.toList(), outputFormat, tokenEstimate, renderHash, modelHints)
    }
}
