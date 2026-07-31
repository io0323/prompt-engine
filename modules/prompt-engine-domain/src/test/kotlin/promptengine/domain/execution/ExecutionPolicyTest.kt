package promptengine.domain.execution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExecutionPolicyTest {
    @Test
    fun `既定値はmaxRetries=2 backoffとparseRepairは既定値`() {
        val policy = ExecutionPolicy(timeoutMs = 5000)

        policy.maxRetries shouldBe 2
        policy.backoff shouldBe BackoffPolicy()
        policy.parseRepair shouldBe ParseRepairPolicy()
    }

    @Test
    fun `timeoutMsが0以下だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ExecutionPolicy(timeoutMs = 0) }
    }

    @Test
    fun `負のmaxRetriesだとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { ExecutionPolicy(timeoutMs = 1000, maxRetries = -1) }
    }
}
