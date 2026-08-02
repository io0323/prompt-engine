package promptengine.domain.audit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** [AuditOutcome]の契約テスト（ADR-0015決定7）。 */
class AuditOutcomeTest {
    @Test
    fun `Success はシングルトンとして等価である`() {
        val a: AuditOutcome = AuditOutcome.Success
        val b: AuditOutcome = AuditOutcome.Success

        a shouldBe b
    }

    @Test
    fun `Failure は同じerrorCodeなら等価 異なれば非等価`() {
        val a: AuditOutcome = AuditOutcome.Failure("VALIDATION_FAILED")
        val b: AuditOutcome = AuditOutcome.Failure("VALIDATION_FAILED")
        val c: AuditOutcome = AuditOutcome.Failure("TOKEN_BUDGET_EXCEEDED")

        a shouldBe b
        a shouldNotBe c
        (a as AuditOutcome.Failure).errorCode shouldBe "VALIDATION_FAILED"
    }
}
