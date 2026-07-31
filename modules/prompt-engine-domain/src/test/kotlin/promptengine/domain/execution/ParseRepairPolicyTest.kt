package promptengine.domain.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ParseRepairPolicyTest {
    @Test
    fun `既定値はenabled=false maxAttempts=2`() {
        val policy = ParseRepairPolicy()

        policy.enabled shouldBe false
        policy.maxAttempts shouldBe 2
    }

    @Test
    fun `負のmaxAttemptsだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ParseRepairPolicy(maxAttempts = -1) }
    }
}
