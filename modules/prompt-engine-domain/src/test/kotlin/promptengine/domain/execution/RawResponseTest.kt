package promptengine.domain.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount

class RawResponseTest {
    private val usage = Usage(TokenCount(10), TokenCount(20))

    @Test
    fun `retryCountの既定値は0`() {
        val response = RawResponse(SensitiveValue.of("ok"), usage, LatencyMs(100))

        response.retryCount shouldBe 0
    }

    @Test
    fun `負のretryCountだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            RawResponse(SensitiveValue.of("ok"), usage, LatencyMs(100), retryCount = -1)
        }
    }

    @Test
    fun `漏洩経路 RawResponse自体をtoString してもcontentの実値は含まれない`() {
        val secretMarker = "sk-real-secret-marker"
        val response = RawResponse(SensitiveValue.of(secretMarker), usage, LatencyMs(100))

        response.toString().shouldNotContain(secretMarker)
    }
}
