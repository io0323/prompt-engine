package promptengine.domain.composition

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.ast.BlockRole

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

    @Test
    fun `SuperWithoutParentBlockException はroleをメッセージに含める`() {
        val exception = SuperWithoutParentBlockException(BlockRole.SYSTEM)

        exception.role shouldBe BlockRole.SYSTEM
        exception.message shouldContain "SYSTEM"
    }

    @Test
    fun `DuplicateSuperCallException はroleをメッセージに含める`() {
        val exception = DuplicateSuperCallException(BlockRole.USER)

        exception.role shouldBe BlockRole.USER
        exception.message shouldContain "USER"
    }

    @Test
    fun `DuplicateImportAliasException はaliasをメッセージに含める`() {
        val exception = DuplicateImportAliasException("safety")

        exception.alias shouldBe "safety"
        exception.message shouldContain "safety"
    }

    @Test
    fun `IncludeRequiredVariableUnresolvedException はFragmentKeyと変数名をメッセージに含める`() {
        val exception = IncludeRequiredVariableUnresolvedException(FragmentKey("fragments/greeting"), "name")

        exception.fragmentKey shouldBe FragmentKey("fragments/greeting")
        exception.variableName shouldBe "name"
        exception.message shouldContain "fragments/greeting"
        exception.message shouldContain "name"
    }

    @Test
    fun `InvalidVariableSubstitutionException は変数名をメッセージに含める`() {
        val exception = InvalidVariableSubstitutionException("k")

        exception.variableName shouldBe "k"
        exception.message shouldContain "k"
    }

    @Test
    fun `MacroNotFoundException はmacro名をメッセージに含める`() {
        val exception = MacroNotFoundException("bulletList")

        exception.macroName shouldBe "bulletList"
        exception.message shouldContain "bulletList"
    }
}
