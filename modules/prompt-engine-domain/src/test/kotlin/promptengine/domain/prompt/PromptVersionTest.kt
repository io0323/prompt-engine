package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.context.ContextRequirement
import promptengine.domain.shared.SemVer
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.ValidationSettings

class PromptVersionTest {
    private val content = PromptContent("Answer: {{question}}")

    @Test
    fun `生成時のデフォルト状態はDraftである`() {
        val version = PromptVersion(semVer = SemVer(0, 1, 0), content = content)

        version.state shouldBe LifecycleState.Draft
    }

    @Test
    fun `variables contextRequirements は省略時に空リストになる`() {
        val version = PromptVersion(semVer = SemVer(0, 1, 0), content = content)

        version.variables shouldBe emptyList()
        version.contextRequirements shouldBe emptyList()
    }

    @Test
    fun `validation は省略時に既定のValidationSettingsになる`() {
        val version = PromptVersion(semVer = SemVer(0, 1, 0), content = content)

        version.validation shouldBe ValidationSettings()
    }

    @Test
    fun `validation を明示的に指定して生成できる`() {
        val settings = ValidationSettings(maxLength = 1000, placeholders = PlaceholderMode.STRICT)

        val version = PromptVersion(semVer = SemVer(0, 1, 0), content = content, validation = settings)

        version.validation shouldBe settings
    }

    @Test
    fun `contextRequirements に同じscopeが重複しているとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            PromptVersion(
                semVer = SemVer(0, 1, 0),
                content = content,
                contextRequirements =
                    listOf(
                        ContextRequirement(scope = "user", required = listOf("id")),
                        ContextRequirement(scope = "user", required = listOf("email")),
                    ),
            )
        }
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
