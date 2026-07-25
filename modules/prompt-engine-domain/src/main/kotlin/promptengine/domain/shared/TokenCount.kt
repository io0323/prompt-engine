package promptengine.domain.shared

/**
 * トークン数（負値禁止、設計書§4.4）。
 */
data class TokenCount(val value: Int) {
    init {
        require(value >= 0) { "value must not be negative: $value" }
    }
}
