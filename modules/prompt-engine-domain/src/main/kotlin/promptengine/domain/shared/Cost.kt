package promptengine.domain.shared

import java.math.BigDecimal

/**
 * 実行コスト（負値禁止、設計書§4.4）。
 */
data class Cost(val value: BigDecimal) {
    init {
        require(value >= BigDecimal.ZERO) { "value must not be negative: $value" }
    }
}
