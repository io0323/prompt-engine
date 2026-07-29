package promptengine.domain.context

/**
 * [ContextResolverImpl]の解決結果（設計書§5.4シーケンス「ContextBindingSet」）。
 *
 * [values] は `"<scope>.<path>"` をキーとするマージ済みの値（マージ順序は§2.7の通り
 * `environment → system → application → workflow → user → memory → conversation`、
 * 後勝ち）。[warnings] は`optional`宣言のスコープ・pathが解決できなかった場合に
 * 積まれる（§5.4「optional: 空データで継続(warning記録)」）。`required`が
 * 解決できなかった場合はwarningではなく[ContextUnavailableException]を投げる。
 */
data class ContextBindingSet(
    val values: Map<String, Any>,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        fun empty(): ContextBindingSet = ContextBindingSet(emptyMap())
    }
}
