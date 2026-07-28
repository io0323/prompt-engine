package promptengine.domain.fragment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FragmentKeyTest {
    @Test
    fun `namespace slash name 形式の文字列でFragmentKeyを生成できる`() {
        FragmentKey("fragments/safety-policy").value shouldBe "fragments/safety-policy"
    }

    @Test
    fun `スラッシュを含まない文字列だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { FragmentKey("safetypolicy") }
    }

    @Test
    fun `大文字を含む文字列だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { FragmentKey("Fragments/SafetyPolicy") }
    }
}
