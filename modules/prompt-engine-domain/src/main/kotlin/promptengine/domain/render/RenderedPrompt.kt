package promptengine.domain.render

import promptengine.domain.shared.TokenCount

/**
 * Render Engineの出力（設計書§2.9・§4.4）。
 *
 * `modelHints`は§2.9本文には記載があるが、§4.4 VO一覧・実装ガイド§6.7のP6スコープ定義には
 * 含まれないため、M1では持たない（APAP連携が具体化するP7以降で追加を検討する、
 * ADR-0013決定6）。
 *
 * [renderHash]は`SHA-256(normalize(messages) + engineId + engineVersion)`
 * （正規化規則・sensitive値の扱いはADR-0013決定1、[promptengine.domain.render.RenderEngine]参照）。
 */
@ConsistentCopyVisibility
data class RenderedPrompt private constructor(
    val messages: List<RenderedMessage>,
    val outputFormat: OutputFormat,
    val tokenEstimate: TokenCount,
    val renderHash: String,
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
        ): RenderedPrompt = RenderedPrompt(messages.toList(), outputFormat, tokenEstimate, renderHash)
    }
}
