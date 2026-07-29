package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.NestedPromptNotSupportedException
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange

/** [IncludeTargetResolver]のテスト（設計書§15.5、ADR-0010決定7）。 */
class IncludeTargetResolverTest {
    private val imports =
        listOf(
            ImportDeclaration("safety", FragmentKey("fragments/safety-policy"), VersionRange.CaretMajor(2)),
        )

    @Test
    fun `宣言済みaliasはimportsの範囲で解決する`() {
        val result = IncludeTargetResolver.resolve("safety", null, imports)

        result shouldBe FragmentReference(FragmentKey("fragments/safety-policy"), VersionRange.CaretMajor(2))
    }

    @Test
    fun `alias参照でもIncludeタグ自身の範囲指定がimportsの範囲より優先される`() {
        val result = IncludeTargetResolver.resolve("safety", "^3", imports)

        result shouldBe FragmentReference(FragmentKey("fragments/safety-policy"), VersionRange.CaretMajor(3))
    }

    @Test
    fun `未宣言のtargetはFragmentKeyの生の値として直接解決する`() {
        val result = IncludeTargetResolver.resolve("fragments/domain-glossary", "1.3.0", imports)

        result shouldBe
            FragmentReference(FragmentKey("fragments/domain-glossary"), VersionRange.Exact(SemVer(1, 3, 0)))
    }

    @Test
    fun `未宣言かつ範囲指定も無いtargetはLatestとして解決する`() {
        val result = IncludeTargetResolver.resolve("fragments/domain-glossary", null, imports)

        result shouldBe FragmentReference(FragmentKey("fragments/domain-glossary"), VersionRange.Latest)
    }

    @Test
    fun `prompt で始まるtargetはNestedPromptNotSupportedExceptionを投げる`() {
        shouldThrow<NestedPromptNotSupportedException> {
            IncludeTargetResolver.resolve("prompt:other/prompt-key", "1", imports)
        }.target shouldBe "prompt:other/prompt-key"
    }
}
