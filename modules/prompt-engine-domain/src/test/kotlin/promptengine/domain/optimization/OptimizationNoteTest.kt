package promptengine.domain.optimization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.TokenCount

class OptimizationNoteTest {
    @Test
    fun `ruleId tokensSaved detail を保持する`() {
        val note = OptimizationNote("TokenOptimization", TokenCount(10), "removed redundant whitespace")

        note.ruleId shouldBe "TokenOptimization"
        note.tokensSaved shouldBe TokenCount(10)
        note.detail shouldBe "removed redundant whitespace"
    }

    @Test
    fun `ruleIdが空文字だと例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            OptimizationNote("", TokenCount(0), "detail")
        }
    }
}
