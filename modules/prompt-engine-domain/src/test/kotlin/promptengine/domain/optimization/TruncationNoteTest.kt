package promptengine.domain.optimization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.TokenCount

class TruncationNoteTest {
    @Test
    fun `scope originalTokenEstimate truncatedTokenEstimate summary を保持する`() {
        val note =
            TruncationNote(
                scope = "conversation",
                originalTokenEstimate = TokenCount(100),
                truncatedTokenEstimate = TokenCount(40),
                summary = "dropped 3 oldest of 8 entries",
            )

        note.scope shouldBe "conversation"
        note.originalTokenEstimate shouldBe TokenCount(100)
        note.truncatedTokenEstimate shouldBe TokenCount(40)
        note.summary shouldBe "dropped 3 oldest of 8 entries"
    }

    @Test
    fun `scopeが空文字だと例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            TruncationNote("", TokenCount(100), TokenCount(40), "summary")
        }
    }

    @Test
    fun `truncatedTokenEstimateがoriginalTokenEstimateを超えると例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            TruncationNote("conversation", TokenCount(40), TokenCount(100), "summary")
        }
    }
}
