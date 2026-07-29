package promptengine.domain.context

/**
 * [ContextResolverImpl]の解決結果（設計書§5.4シーケンス「ContextBindingSet」）。
 *
 * [values] は `"<scope>.<path>"` をキーとするマージ済みの値（マージ順序は§2.7の通り
 * `environment → system → application → workflow → user → memory → conversation`、
 * 後勝ち）。[warnings] は`optional`宣言のスコープ・pathが解決できなかった場合に
 * 積まれる（§5.4「optional: 空データで継続(warning記録)」）。`required`が
 * 解決できなかった場合はwarningではなく[ContextUnavailableException]を投げる。
 *
 * [BindingSet][promptengine.domain.variable.BindingSet]と同様、コンストラクタ引数として
 * 渡された`values`/`warnings`をそのまま保持せず不変コピーを取る。呼出元が渡した
 * `MutableMap`/`MutableList`を構築後に変更しても、このインスタンスの状態は影響を受けない。
 */
class ContextBindingSet(
    values: Map<String, Any>,
    warnings: List<String> = emptyList(),
) {
    val values: Map<String, Any> = values.toMap()
    val warnings: List<String> = warnings.toList()

    override fun equals(other: Any?): Boolean =
        other is ContextBindingSet && values == other.values && warnings == other.warnings

    override fun hashCode(): Int = 31 * values.hashCode() + warnings.hashCode()

    override fun toString(): String = "ContextBindingSet(values=$values, warnings=$warnings)"

    companion object {
        /** `values`/`warnings`いずれも空の[ContextBindingSet]を返す。 */
        fun empty(): ContextBindingSet = ContextBindingSet(emptyMap())
    }
}
