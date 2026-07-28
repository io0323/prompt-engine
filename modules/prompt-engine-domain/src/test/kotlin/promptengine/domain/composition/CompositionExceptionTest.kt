package promptengine.domain.composition

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey

class CompositionExceptionTest {
    @Test
    fun `CircularDependencyException はサイクルの経路をメッセージに含める`() {
        val exception = CircularDependencyException(listOf("prompt/a", "templates/b", "prompt/a"))

        exception.cyclePath shouldBe listOf("prompt/a", "templates/b", "prompt/a")
        exception.message shouldContain "prompt/a -> templates/b -> prompt/a"
    }

    @Test
    fun `CompositionDepthExceededException は上限をメッセージに含める`() {
        val exception = CompositionDepthExceededException(5)

        exception.maxDepth shouldBe 5
        exception.message shouldContain "5"
    }

    @Test
    fun `CompositionSizeExceededException は上限と実際のサイズをメッセージに含める`() {
        val exception = CompositionSizeExceededException(maxSizeBytes = 1_000_000, actualSizeBytes = 1_200_000)

        exception.maxSizeBytes shouldBe 1_000_000
        exception.actualSizeBytes shouldBe 1_200_000
        exception.message shouldContain "1200000"
        exception.message shouldContain "1000000"
    }

    @Test
    fun `TemplateReferenceNotFoundException はkeyと範囲をメッセージに含める`() {
        val exception = TemplateReferenceNotFoundException(TemplateKey("templates/base"), VersionRange.CaretMajor(2))

        exception.message shouldContain "templates/base"
        exception.message shouldContain "^2"
    }

    @Test
    fun `FragmentReferenceNotFoundException はkeyと範囲をメッセージに含める`() {
        val exception =
            FragmentReferenceNotFoundException(FragmentKey("fragments/safety-policy"), VersionRange.Latest)

        exception.message shouldContain "fragments/safety-policy"
        exception.message shouldContain "latest"
    }

    @Test
    fun `DraftReferenceNotAllowedException は参照の説明をメッセージに含める`() {
        val exception = DraftReferenceNotAllowedException("templates/base@1.0.0")

        exception.message shouldContain "templates/base@1.0.0"
    }

    @Test
    fun `MacroRecursionException はマクロ名をメッセージに含める`() {
        val exception = MacroRecursionException("bulletList")

        exception.macroName shouldBe "bulletList"
        exception.message shouldContain "bulletList"
    }

    @Test
    fun `NestedPromptNotSupportedException はtargetをメッセージに含める`() {
        val exception = NestedPromptNotSupportedException("prompt:other/prompt-key@1")

        exception.target shouldBe "prompt:other/prompt-key@1"
        exception.message shouldContain "prompt:other/prompt-key@1"
    }
}
