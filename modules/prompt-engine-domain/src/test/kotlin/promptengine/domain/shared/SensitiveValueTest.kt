package promptengine.domain.shared

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class SensitiveValueTest {
    @Test
    fun `toString は常にマスク文字列を返す`() {
        SensitiveValue.of("api-secret-12345").toString() shouldBe "***"
    }

    @Test
    fun `toString の結果に元の値が含まれない`() {
        SensitiveValue.of("api-secret-12345").toString() shouldNotContain "api-secret-12345"
    }

    @Test
    fun `expose は元の値を返す`() {
        SensitiveValue.of("api-secret-12345").expose() shouldBe "api-secret-12345"
    }

    @Test
    fun `data classではないため copy や分割代入で生値を取得する手段が存在しない`() {
        val declaredMethodNames = SensitiveValue::class.java.declaredMethods.map { it.name }

        declaredMethodNames shouldNotContain "copy"
        declaredMethodNames shouldNotContain "copy\$default"
        declaredMethodNames shouldNotContain "component1"
    }

    @Test
    fun `同じ値を持つ2つのインスタンスは等しくhashCodeも一致する`() {
        val first = SensitiveValue.of("api-secret-12345")
        val second = SensitiveValue.of("api-secret-12345")

        first shouldBe second
        first.hashCode() shouldBe second.hashCode()
    }

    @Test
    fun `異なる値を持つ2つのインスタンスは等しくない`() {
        (SensitiveValue.of("secret-a") == SensitiveValue.of("secret-b")) shouldBe false
    }

    @Test
    fun `SensitiveValue以外の型とは等しくない`() {
        @Suppress("EqualsIncompatibleType", "SENSELESS_COMPARISON")
        val result = SensitiveValue.of("api-secret-12345").equals("api-secret-12345")

        result shouldBe false
    }
}
