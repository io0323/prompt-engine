package promptengine.engine.validation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.TokenCount

class NaiveTokenizerPluginTest {
    private val plugin = NaiveTokenizerPlugin()

    @Test
    fun `4文字ちょうどは1トークンとして推定する`() {
        plugin.estimate("abcd") shouldBe TokenCount(1)
    }

    @Test
    fun `割り切れない文字数は切り上げる`() {
        plugin.estimate("abcde") shouldBe TokenCount(2)
    }

    @Test
    fun `空文字列は0トークン`() {
        plugin.estimate("") shouldBe TokenCount(0)
    }
}
