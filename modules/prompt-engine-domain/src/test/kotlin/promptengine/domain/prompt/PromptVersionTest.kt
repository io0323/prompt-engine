package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SemVer

class PromptVersionTest {
    private val content = PromptContent("Answer: {{question}}")

    @Test
    fun `生成時のデフォルト状態はDraftである`() {
        val version = PromptVersion(semVer = SemVer(0, 1, 0), content = content)

        version.state shouldBe LifecycleState.Draft
    }

    @Test
    fun `variables contextRequirement は省略時に空 null になる`() {
        val version = PromptVersion(semVer = SemVer(0, 1, 0), content = content)

        version.variables shouldBe emptyList()
        version.contextRequirement shouldBe null
    }

    @Test
    fun `Draft状態であればwithContentで内容を差し替えられる`() {
        val version = PromptVersion(semVer = SemVer(0, 1, 0), content = content)
        val newContent = PromptContent("Answer(v2): {{question}}")

        val updated = version.withContent(newContent)

        updated.content shouldBe newContent
    }

    @Test
    fun `Published状態のVersionはwithContentで内容を変更しようとするとInvalidStateTransitionExceptionを投げる`() {
        val published = PromptVersion(semVer = SemVer(0, 1, 0), content = content, state = LifecycleState.Published)

        shouldThrow<InvalidStateTransitionException> {
            published.withContent(PromptContent("Answer(tampered): {{question}}"))
        }
    }
}
