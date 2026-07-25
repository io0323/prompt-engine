package promptengine.domain.context

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContextRequirementTest {
    @Test
    fun `scope required optional を指定して生成できる`() {
        val requirement =
            ContextRequirement(
                scope = "session",
                required = listOf("user.id"),
                optional = listOf("user.locale"),
            )

        requirement.scope shouldBe "session"
        requirement.required shouldBe listOf("user.id")
        requirement.optional shouldBe listOf("user.locale")
    }

    @Test
    fun `required optional は省略時に空リストになる`() {
        val requirement = ContextRequirement(scope = "session")

        requirement.required shouldBe emptyList()
        requirement.optional shouldBe emptyList()
    }

    @Test
    fun `scope が空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            ContextRequirement(scope = "")
        }
    }

    @Test
    fun `同じpathがrequiredとoptionalの両方に存在するとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            ContextRequirement(
                scope = "session",
                required = listOf("user.id"),
                optional = listOf("user.id"),
            )
        }
    }
}
