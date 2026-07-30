package promptengine.engine.optimization

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalTextTest {
    @Test
    fun `文字列はそのまま返す`() {
        canonicalString("hello") shouldBe "hello"
    }

    @Test
    fun `nullは空文字になる`() {
        canonicalString(null) shouldBe ""
    }

    @Test
    fun `MapはキーでソートしてMap挿入順に依存しない`() {
        val orderA = linkedMapOf("role" to "user", "content" to "hi")
        val orderB = linkedMapOf("content" to "hi", "role" to "user")

        canonicalString(orderA) shouldBe canonicalString(orderB)
        canonicalString(orderA) shouldBe "content=hi,role=user"
    }

    @Test
    fun `Listは各要素をcanonicalStringで連結する`() {
        canonicalString(listOf("a", "b")) shouldBe "a,b"
    }

    @Test
    fun `Listがネストしたnullを含んでも空文字として連結する`() {
        canonicalString(listOf("a", null)) shouldBe "a,"
    }

    @Test
    fun `MapがListやMapを値に持つ場合も再帰的に正規化する`() {
        val message = linkedMapOf("role" to "user", "tags" to listOf("x", "y"))

        canonicalString(message) shouldBe "role=user,tags=x,y"
    }
}
