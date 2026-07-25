package promptengine.domain.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LatencyMsTest {
    @Test
    fun `0以上の値でLatencyMsを生成できる`() {
        LatencyMs(0L).value shouldBe 0L
        LatencyMs(1500L).value shouldBe 1500L
    }

    @Test
    fun `負の値だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { LatencyMs(-1L) }
    }
}
