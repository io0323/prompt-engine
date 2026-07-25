package promptengine.domain.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TokenCountTest {
    @Test
    fun `0以上の値でTokenCountを生成できる`() {
        TokenCount(0).value shouldBe 0
        TokenCount(100).value shouldBe 100
    }

    @Test
    fun `負の値だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { TokenCount(-1) }
    }
}
