package promptengine.domain.experiment

/**
 * トラフィック分割方針（設計書§4.4 `TrafficPolicy | variant→重み(%)、sticky key（userId等）`、
 * ADR-0034）。
 *
 * 重み配分自体は各[Variant.weightPct]が持つ（合計100%、[Experiment]の不変条件）ため、
 * 本VOは[stickyKeyPath]のみを持つ。
 *
 * [stickyKeyPath]は[promptengine.domain.shared.PromptRequest.contextData]から値を読む
 * ドット区切りのパス（例 `"user.id"`、`contextData["user"]["id"]`に対応）。`null`は
 * 「sticky割当を行わず重み付き純粋ランダムのみ」を意味する（ADR-0034決定3）。
 */
data class TrafficPolicy(
    val stickyKeyPath: String? = null,
) {
    init {
        require(stickyKeyPath == null || stickyKeyPath.isNotBlank()) { "stickyKeyPath must not be blank" }
    }
}
