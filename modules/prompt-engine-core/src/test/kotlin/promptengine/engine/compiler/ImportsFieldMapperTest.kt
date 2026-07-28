package promptengine.engine.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.composition.DuplicateImportAliasException
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange

/** [ImportsFieldMapper]のテスト（設計書§15.4、ADR-0010決定7）。 */
class ImportsFieldMapperTest {
    @Test
    fun `nullなら空リストを返す`() {
        ImportsFieldMapper.parse(null).shouldBeEmpty()
    }

    @Test
    fun `alias と ref の版範囲付きエントリを解決する`() {
        val raw = listOf(mapOf("alias" to "safety", "ref" to "fragments/safety-policy@^2"))

        val result = ImportsFieldMapper.parse(raw)

        result shouldBe
            listOf(
                ImportDeclaration("safety", FragmentKey("fragments/safety-policy"), VersionRange.CaretMajor(2)),
            )
    }

    @Test
    fun `版範囲が無いrefはLatestとして解決する`() {
        val raw = listOf(mapOf("alias" to "glossary", "ref" to "fragments/domain-glossary"))

        val result = ImportsFieldMapper.parse(raw)

        result shouldBe
            listOf(ImportDeclaration("glossary", FragmentKey("fragments/domain-glossary"), VersionRange.Latest))
    }

    @Test
    fun `完全一致のSemVer範囲も解決する`() {
        val raw = listOf(mapOf("alias" to "glossary", "ref" to "fragments/domain-glossary@1.3.0"))

        val result = ImportsFieldMapper.parse(raw)

        result.single().range shouldBe VersionRange.Exact(SemVer(1, 3, 0))
    }

    @Test
    fun `複数エントリを順序通りに解決する`() {
        val raw =
            listOf(
                mapOf("alias" to "safety", "ref" to "fragments/safety-policy@^2"),
                mapOf("alias" to "glossary", "ref" to "fragments/domain-glossary@1.3.0"),
            )

        val result = ImportsFieldMapper.parse(raw)

        result.map { it.alias } shouldBe listOf("safety", "glossary")
    }

    @Test
    fun `同一aliasが2回宣言されるとDuplicateImportAliasExceptionを投げる`() {
        val raw =
            listOf(
                mapOf("alias" to "safety", "ref" to "fragments/safety-policy@^2"),
                mapOf("alias" to "safety", "ref" to "fragments/other-policy@^1"),
            )

        shouldThrow<DuplicateImportAliasException> {
            ImportsFieldMapper.parse(raw)
        }.alias shouldBe "safety"
    }

    @Test
    fun `imports がリストでない場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ImportsFieldMapper.parse("not-a-list")
        }
    }

    @Test
    fun `エントリがマッピングでない場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ImportsFieldMapper.parse(listOf("not-a-map"))
        }
    }

    @Test
    fun `aliasが欠落している場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ImportsFieldMapper.parse(listOf(mapOf("ref" to "fragments/safety-policy@^2")))
        }
    }

    @Test
    fun `refが欠落している場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> {
            ImportsFieldMapper.parse(listOf(mapOf("alias" to "safety")))
        }
    }
}
