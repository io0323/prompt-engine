package promptengine.domain.context

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContextBindingSetTest {
    @Test
    fun `values warningsを指定して生成できる`() {
        val bindingSet =
            ContextBindingSet(
                values = mapOf("user.id" to "user-1"),
                warnings = listOf("optional context not resolved: user.locale"),
            )

        bindingSet.values shouldBe mapOf("user.id" to "user-1")
        bindingSet.warnings shouldBe listOf("optional context not resolved: user.locale")
    }

    @Test
    fun `warningsは省略時に空リストになる`() {
        val bindingSet = ContextBindingSet(values = mapOf("user.id" to "user-1"))

        bindingSet.warnings shouldBe emptyList()
    }

    @Test
    fun `emptyは空のContextBindingSetを返す`() {
        val bindingSet = ContextBindingSet.empty()

        bindingSet.values shouldBe emptyMap()
        bindingSet.warnings shouldBe emptyList()
    }

    @Test
    fun `同じvalues warningsを持つ2つのContextBindingSetは構造的に等しい`() {
        val a = ContextBindingSet(mapOf("user.id" to "user-1"))
        val b = ContextBindingSet(mapOf("user.id" to "user-1"))

        a shouldBe b
    }
}
