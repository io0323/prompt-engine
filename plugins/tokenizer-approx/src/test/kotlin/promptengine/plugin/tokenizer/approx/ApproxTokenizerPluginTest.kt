package promptengine.plugin.tokenizer.approx

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.shared.TokenCount

class ApproxTokenizerPluginTest {
    private val tokenizer = ApproxTokenizerPlugin()

    @Test
    fun `空文字列は0トークン`() {
        tokenizer.estimate("") shouldBe TokenCount(0)
    }

    @Test
    fun `ASCII文字は4文字あたり1トークンとして切り上げる`() {
        tokenizer.estimate("abcd") shouldBe TokenCount(1)
        tokenizer.estimate("abcde") shouldBe TokenCount(2)
    }

    @Test
    fun `CJK文字は1文字1トークンとして数える`() {
        tokenizer.estimate("こんにちは") shouldBe TokenCount(5)
    }

    @Test
    fun `漢字とひらがなとカタカナとハングルを全てCJKとして扱う`() {
        tokenizer.estimate("漢") shouldBe TokenCount(1)
        tokenizer.estimate("ひ") shouldBe TokenCount(1)
        tokenizer.estimate("カ") shouldBe TokenCount(1)
        tokenizer.estimate("한") shouldBe TokenCount(1)
    }

    @Test
    fun `CJKとASCIIの混在は重み付けの合計を切り上げる`() {
        // CJK 2文字(2.0) + ASCII 2文字(0.5) = 2.5 -> 切り上げ3
        tokenizer.estimate("漢字ab") shouldBe TokenCount(3)
    }

    @Test
    fun `同一入力からは常に同一の結果を返す`() {
        val text = "The quick brown fox 漢字かなカナ한글 123!?"

        val results = (1..100).map { tokenizer.estimate(text) }.toSet()

        results shouldBe setOf(tokenizer.estimate(text))
    }

    @Test
    fun `空白や記号はその他文字として扱う`() {
        tokenizer.estimate("    ") shouldBe TokenCount(1)
        tokenizer.estimate("!!!!") shouldBe TokenCount(1)
    }
}
