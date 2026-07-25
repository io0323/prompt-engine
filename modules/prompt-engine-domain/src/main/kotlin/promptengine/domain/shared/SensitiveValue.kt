package promptengine.domain.shared

/**
 * 機密値のラッパー（設計書CLAUDE.md「Secret / sensitive=trueの変数値は絶対に出力しない」）。
 * data classにはしない。componentN()/copy()経由で生値を取得できてしまうことを避けるため。
 */
class SensitiveValue private constructor(private val raw: String) {
    fun expose(): String = raw

    override fun toString(): String = MASK

    override fun equals(other: Any?): Boolean = other is SensitiveValue && raw == other.raw

    override fun hashCode(): Int = raw.hashCode()

    companion object {
        private const val MASK = "***"

        fun of(raw: String): SensitiveValue = SensitiveValue(raw)
    }
}
