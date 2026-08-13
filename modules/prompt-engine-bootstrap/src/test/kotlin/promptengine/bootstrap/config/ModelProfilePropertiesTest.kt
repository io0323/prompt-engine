package promptengine.bootstrap.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

/** [ModelProfileProperties]の`init`検証（M2-1c、ADR-0030決定1）。 */
class ModelProfilePropertiesTest {
    @Test
    fun `maxContextTokensが0以下なら構築時に例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ModelProfileProperties(maxContextTokens = 0)
        }
    }

    @Test
    fun `tokenizerIdが空白なら構築時に例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ModelProfileProperties(tokenizerId = "  ")
        }
    }

    @Test
    fun `costPerTokenが不正な数値文字列なら構築時に例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ModelProfileProperties(costPerToken = "not-a-number")
        }
    }

    @Test
    fun `既定値で問題なく構築できる`() {
        shouldNotThrowAny { ModelProfileProperties() }
    }
}
