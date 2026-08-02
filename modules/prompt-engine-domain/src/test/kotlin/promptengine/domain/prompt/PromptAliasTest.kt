package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SemVer

class PromptAliasTest {
    @Test
    fun `promptKey alias semVerでPromptAliasを生成できる`() {
        val key = PromptKey("support/faq-answer")

        val alias = PromptAlias(key, "stable", SemVer(1, 0, 0))

        alias.promptKey shouldBe key
        alias.alias shouldBe "stable"
        alias.semVer shouldBe SemVer(1, 0, 0)
    }

    @Test
    fun `空文字のaliasはIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            PromptAlias(PromptKey("support/faq-answer"), "", SemVer(1, 0, 0))
        }
    }

    @Test
    fun `空白のみのaliasはIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            PromptAlias(PromptKey("support/faq-answer"), "   ", SemVer(1, 0, 0))
        }
    }
}
