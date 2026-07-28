package promptengine.domain.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * extendsのVersion範囲（設計書§15.1/§15.3 `extends: <templateKey>[@versionRange]`）を
 * 表すVOのテスト（ADR-0009）。
 */
class VersionRangeTest {
    @Test
    fun `parse はnullをLatestとして解釈する`() {
        VersionRange.parse(null) shouldBe VersionRange.Latest
    }

    @Test
    fun `parse は空文字列や空白のみの明示指定にIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { VersionRange.parse("") }
        shouldThrow<IllegalArgumentException> { VersionRange.parse("   ") }
    }

    @Test
    fun `parse はキャレット記法をCaretMajorとして解釈する`() {
        VersionRange.parse("^2") shouldBe VersionRange.CaretMajor(2)
    }

    @Test
    fun `parse は完全なSemVer文字列をExactとして解釈する`() {
        VersionRange.parse("1.3.0") shouldBe VersionRange.Exact(SemVer(1, 3, 0))
    }

    @Test
    fun `parse は不正なキャレット記法にIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { VersionRange.parse("^x") }
    }

    @Test
    fun `parse は不正なSemVer文字列にIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { VersionRange.parse("1.3") }
        shouldThrow<IllegalArgumentException> { VersionRange.parse("1.x.0") }
    }

    @Test
    fun `CaretMajor は負のmajorを許さない`() {
        shouldThrow<IllegalArgumentException> { VersionRange.CaretMajor(-1) }
    }

    @Test
    fun `Latest は任意のSemVerにマッチする`() {
        VersionRange.Latest.matches(SemVer(0, 0, 1)) shouldBe true
        VersionRange.Latest.matches(SemVer(9, 9, 9)) shouldBe true
    }

    @Test
    fun `CaretMajor は同じmajorのみにマッチする`() {
        val range = VersionRange.CaretMajor(2)

        range.matches(SemVer(2, 0, 0)) shouldBe true
        range.matches(SemVer(2, 9, 9)) shouldBe true
        range.matches(SemVer(1, 9, 9)) shouldBe false
        range.matches(SemVer(3, 0, 0)) shouldBe false
    }

    @Test
    fun `Exact は完全一致のみにマッチする`() {
        val range = VersionRange.Exact(SemVer(1, 3, 0))

        range.matches(SemVer(1, 3, 0)) shouldBe true
        range.matches(SemVer(1, 3, 1)) shouldBe false
    }

    @Test
    fun `toRangeText はLatestに対してnullを返す`() {
        VersionRange.Latest.toRangeText() shouldBe null
    }

    @Test
    fun `toRangeText はCaretMajorに対してキャレット記法の文字列を返す`() {
        VersionRange.CaretMajor(2).toRangeText() shouldBe "^2"
    }

    @Test
    fun `toRangeText はExactに対してSemVerの文字列表現を返す`() {
        VersionRange.Exact(SemVer(1, 3, 0)).toRangeText() shouldBe "1.3.0"
    }

    @Test
    fun `parseとtoRangeTextは往復する`() {
        listOf("^2", "1.3.0", null).forEach { text ->
            VersionRange.parse(text).toRangeText() shouldBe text
        }
    }
}
