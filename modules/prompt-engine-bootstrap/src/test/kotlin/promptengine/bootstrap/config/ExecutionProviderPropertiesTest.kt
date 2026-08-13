package promptengine.bootstrap.config

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

/** [ExecutionProviderProperties]の`init`検証（ADR-0030決定4、ADR-0031）。 */
class ExecutionProviderPropertiesTest {
    @Test
    fun `providerが空白なら構築時に例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ExecutionProviderProperties(provider = "  ")
        }
    }

    @Test
    fun `既定値で問題なく構築できる`() {
        shouldNotThrowAny { ExecutionProviderProperties() }
    }
}
