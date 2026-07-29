package promptengine.domain.variable

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import promptengine.domain.shared.SensitiveValue

private const val SECRET_VALUE = "sk-live-do-not-leak-12345"

class BindingSetTest {
    @Test
    fun `nameでget及びcontainsKeyできる`() {
        val bindings = BindingSet(mapOf("tone" to "polite"))

        bindings["tone"] shouldBe "polite"
        bindings.containsKey("tone") shouldBe true
        bindings.containsKey("missing") shouldBe false
        bindings["missing"] shouldBe null
    }

    @Test
    fun `emptyは空のBindingSetを返す`() {
        val bindings = BindingSet.empty()

        bindings.values shouldBe emptyMap()
    }

    @Test
    fun `同じvaluesを持つ2つのBindingSetは構造的に等しい`() {
        val a = BindingSet(mapOf("tone" to "polite"))
        val b = BindingSet(mapOf("tone" to "polite"))
        val c = BindingSet(mapOf("tone" to "casual"))

        a shouldBe b
        (a == c) shouldBe false
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `toStringはSensitiveValueの実値を含まずキー一覧のみを出す`() {
        val bindings = BindingSet(mapOf("apiKeyRef" to SensitiveValue.of(SECRET_VALUE), "tone" to "polite"))

        val text = bindings.toString()

        text shouldNotContain SECRET_VALUE
        text shouldBe "BindingSet(keys=${setOf("apiKeyRef", "tone")})"
    }
}
