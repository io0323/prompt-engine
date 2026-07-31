package promptengine.domain.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BackoffPolicyTest {
    @Test
    fun `既定値では1回目2回目3回目のdelayFor が指数的に増える`() {
        val policy = BackoffPolicy()

        policy.delayFor(1) shouldBe 500L
        policy.delayFor(2) shouldBe 1000L
        policy.delayFor(3) shouldBe 2000L
    }

    @Test
    fun `maxDelayMsで頭打ちする`() {
        val policy = BackoffPolicy(initialDelayMs = 500, multiplier = 2.0, maxDelayMs = 1200)

        policy.delayFor(1) shouldBe 500L
        policy.delayFor(2) shouldBe 1000L
        policy.delayFor(3) shouldBe 1200L
        policy.delayFor(10) shouldBe 1200L
    }

    @Test
    fun `attemptが1未満だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { BackoffPolicy().delayFor(0) }
    }

    @Test
    fun `multiplierが1未満だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { BackoffPolicy(multiplier = 0.5) }
    }

    @Test
    fun `maxDelayMsがinitialDelayMs未満だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { BackoffPolicy(initialDelayMs = 1000, maxDelayMs = 500) }
    }

    @Test
    fun `負のinitialDelayMsだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { BackoffPolicy(initialDelayMs = -1) }
    }
}
