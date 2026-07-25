package promptengine.domain.prompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SemVer

class VersionRefTest {
    @Test
    fun `Fixed は固定のSemVerを保持する`() {
        val ref = VersionRef.Fixed(SemVer(1, 0, 0))

        ref.semVer shouldBe SemVer(1, 0, 0)
    }

    @Test
    fun `Latest は常に同一のインスタンスを指す`() {
        (VersionRef.Latest === VersionRef.Latest) shouldBe true
    }

    @Test
    fun `Alias はエイリアス名を保持する`() {
        val ref = VersionRef.Alias("stable")

        ref.name shouldBe "stable"
    }

    @Test
    fun `Alias の名前が空文字だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { VersionRef.Alias("") }
    }
}
