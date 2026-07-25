package promptengine.domain.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SemVerTest {
    @Test
    fun `major minor patch からSemVerを生成できる`() {
        val semVer = SemVer(1, 2, 3)

        semVer.major shouldBe 1
        semVer.minor shouldBe 2
        semVer.patch shouldBe 3
    }

    @Test
    fun `toString は major minor patch をドット区切りで返す`() {
        SemVer(1, 2, 3).toString() shouldBe "1.2.3"
    }

    @Test
    fun `major minor patch のいずれかが負の値だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { SemVer(-1, 0, 0) }
        shouldThrow<IllegalArgumentException> { SemVer(0, -1, 0) }
        shouldThrow<IllegalArgumentException> { SemVer(0, 0, -1) }
    }

    @Test
    fun `compareTo はmajor minor patchの順で大小比較する`() {
        (SemVer(1, 0, 0) < SemVer(2, 0, 0)) shouldBe true
        (SemVer(1, 2, 0) < SemVer(1, 3, 0)) shouldBe true
        (SemVer(1, 2, 3) < SemVer(1, 2, 4)) shouldBe true
        (SemVer(1, 2, 3) == SemVer(1, 2, 3)) shouldBe true
    }
}
