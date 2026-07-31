package promptengine.domain.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount

class RawResponseTest {
    private val usage = Usage(TokenCount(10), TokenCount(20))

    @Test
    fun `retryCountの既定値は0`() {
        val response = RawResponse("ok", usage, LatencyMs(100))

        response.retryCount shouldBe 0
    }

    @Test
    fun `負のretryCountだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { RawResponse("ok", usage, LatencyMs(100), retryCount = -1) }
    }
}
