package promptengine.domain.template

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TemplateKeyTest {
    @Test
    fun `namespaceとnameをスラッシュで区切った文字列からTemplateKeyを生成できる`() {
        TemplateKey("shared/base-instructions").value shouldBe "shared/base-instructions"
    }

    @Test
    fun `スラッシュを含まない文字列だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { TemplateKey("baseinstructions") }
    }

    @Test
    fun `大文字を含む文字列だとIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { TemplateKey("Shared/BaseInstructions") }
    }
}
