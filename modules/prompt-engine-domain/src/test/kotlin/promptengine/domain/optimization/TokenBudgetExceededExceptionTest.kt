package promptengine.domain.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.TokenCount

class TokenBudgetExceededExceptionTest {
    @Test
    fun `estimated budget を保持しメッセージにTOKEN_BUDGET_EXCEEDEDを含む`() {
        val exception = TokenBudgetExceededException(TokenCount(9000), TokenCount(8000))

        exception.estimated shouldBe TokenCount(9000)
        exception.budget shouldBe TokenCount(8000)
        exception.message shouldBe "TOKEN_BUDGET_EXCEEDED: estimated 9000 exceeds budget 8000"
    }
}
