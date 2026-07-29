package promptengine.domain.validation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ValidationSettingsTest {
    @Test
    fun `省略時は無制限かつlenientになる`() {
        val settings = ValidationSettings()

        settings.maxLength shouldBe null
        settings.maxTokens shouldBe null
        settings.policies shouldBe emptyList()
        settings.placeholders shouldBe PlaceholderMode.LENIENT
    }

    @Test
    fun `maxLength maxTokens policies placeholders を指定して生成できる`() {
        val settings =
            ValidationSettings(
                maxLength = 32000,
                maxTokens = 8000,
                policies = listOf("no-pii", "corporate-tone"),
                placeholders = PlaceholderMode.STRICT,
            )

        settings.maxLength shouldBe 32000
        settings.maxTokens shouldBe 8000
        settings.policies shouldBe listOf("no-pii", "corporate-tone")
        settings.placeholders shouldBe PlaceholderMode.STRICT
    }

    @Test
    fun `maxLengthが0以下なら例外を投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationSettings(maxLength = 0) }
        shouldThrow<IllegalArgumentException> { ValidationSettings(maxLength = -1) }
    }

    @Test
    fun `maxTokensが0以下なら例外を投げる`() {
        shouldThrow<IllegalArgumentException> { ValidationSettings(maxTokens = 0) }
        shouldThrow<IllegalArgumentException> { ValidationSettings(maxTokens = -1) }
    }
}
