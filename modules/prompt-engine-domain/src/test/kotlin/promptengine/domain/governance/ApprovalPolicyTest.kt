package promptengine.domain.governance

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ApprovalPolicyTest {
    @Test
    fun `requiredApprovalsが1以上なら構築できる`() {
        val policy = ApprovalPolicy(requiredApprovals = 1, allowSelfApproval = false)

        policy.requiredApprovals shouldBe 1
    }

    @Test
    fun `requiredApprovalsが1未満ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            ApprovalPolicy(requiredApprovals = 0, allowSelfApproval = false)
        }
    }
}
