package promptengine.domain.prompt

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SemVer

class PromptVersionStateNotAllowedExceptionTest {
    @Test
    fun `semVerと状態をメッセージに含める`() {
        val exception = PromptVersionStateNotAllowedException(SemVer(1, 0, 0), LifecycleState.Draft)

        exception.semVer shouldBe SemVer(1, 0, 0)
        exception.state shouldBe LifecycleState.Draft
        exception.message shouldContain "1.0.0"
        exception.message shouldContain "Draft"
    }
}
