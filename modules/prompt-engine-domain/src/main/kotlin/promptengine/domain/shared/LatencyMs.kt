package promptengine.domain.shared

/**
 * 実行レイテンシ（ミリ秒、負値禁止、設計書§4.4）。
 */
data class LatencyMs(val value: Long) {
    init {
        require(value >= 0) { "value must not be negative: $value" }
    }
}
