package promptengine.domain.variable

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class VariableUnresolvedExceptionTest {
    @Test
    fun `missingNamesを保持しmessageに含める`() {
        val exception = VariableUnresolvedException(listOf("apiKeyRef", "question"))

        exception.missingNames shouldBe listOf("apiKeyRef", "question")
        exception.message.orEmpty() shouldContain "apiKeyRef"
        exception.message.orEmpty() shouldContain "question"
    }

    @Test
    fun `missingNamesが空だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            VariableUnresolvedException(emptyList())
        }
    }
}
