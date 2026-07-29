package promptengine.domain.context

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ContextUnavailableExceptionTest {
    @Test
    fun `missingRequirementsを保持しmessageに含める`() {
        val exception = ContextUnavailableException(listOf("system.locale", "user.email"))

        exception.missingRequirements shouldBe listOf("system.locale", "user.email")
        exception.message.orEmpty() shouldContain "system.locale"
        exception.message.orEmpty() shouldContain "user.email"
    }

    @Test
    fun `missingRequirementsが空だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            ContextUnavailableException(emptyList())
        }
    }
}
