package promptengine.domain.optimization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.Cost
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal

class ModelProfileTest {
    @Test
    fun `maxContextTokens tokenizerId costPerToken capabilities を保持する`() {
        val profile =
            ModelProfile(
                maxContextTokens = TokenCount(8000),
                tokenizerId = "approx-v1",
                costPerToken = Cost(BigDecimal("0.001")),
                capabilities = setOf(ModelCapability.WEAK_INSTRUCTION_FOLLOWING),
            )

        profile.maxContextTokens shouldBe TokenCount(8000)
        profile.tokenizerId shouldBe "approx-v1"
        profile.costPerToken shouldBe Cost(BigDecimal("0.001"))
        profile.capabilities shouldBe setOf(ModelCapability.WEAK_INSTRUCTION_FOLLOWING)
    }

    @Test
    fun `capabilitiesの既定値は空集合`() {
        val profile = ModelProfile(TokenCount(8000), "approx-v1", Cost(BigDecimal.ZERO))

        profile.capabilities shouldBe emptySet()
    }

    @Test
    fun `tokenizerIdが空文字だと例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ModelProfile(TokenCount(8000), "", Cost(BigDecimal.ZERO))
        }
    }
}
