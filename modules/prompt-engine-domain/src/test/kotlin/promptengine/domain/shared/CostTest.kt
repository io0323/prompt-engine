package promptengine.domain.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CostTest {
    @Test
    fun `0以上の値でCostを生成できる`() {
        Cost(BigDecimal.ZERO).value shouldBe BigDecimal.ZERO
        Cost(BigDecimal("1.23")).value shouldBe BigDecimal("1.23")
    }

    @Test
    fun `負の値だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { Cost(BigDecimal("-0.01")) }
    }
}
