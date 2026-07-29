package promptengine.domain.variable

import promptengine.domain.shared.SensitiveValue

/**
 * 解決済み変数の不変Map（設計書§4.4「name→Value の不変Map（sensitive値はマスク表示）」）。
 *
 * `sensitive=true`の変数の値は[promptengine.domain.shared.SensitiveValue]でラップされた
 * 状態で[values]に格納される（[VariableResolverChain]がラップ後の値のみを渡す）。
 * [toString] はMap全体をそのまま連結して返さず、キー一覧のみを出す
 * （値の`toString()`が呼ばれても`SensitiveValue.toString()`のマスクにより実値は出ないが、
 * 二重の安全網としてBindingSet自体もMapの中身を丸ごとは出力しない）。
 */
class BindingSet(values: Map<String, Any>) {
    val values: Map<String, Any> = values.toMap()

    operator fun get(name: String): Any? = values[name]

    fun containsKey(name: String): Boolean = values.containsKey(name)

    override fun toString(): String = "BindingSet(keys=${values.keys})"

    override fun equals(other: Any?): Boolean = other is BindingSet && values == other.values

    override fun hashCode(): Int = values.hashCode()

    companion object {
        fun empty(): BindingSet = BindingSet(emptyMap())
    }
}
