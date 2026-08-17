package promptengine.engine.benchmark

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.text.Normalizer

/**
 * [NormalizedExactMatchScoringRule]のテスト（設計書§16-15、ADR-0035決定4）。
 */
class NormalizedExactMatchScoringRuleTest {
    private val rule = NormalizedExactMatchScoringRule()

    @Test
    fun `metricType はAccuracy`() {
        rule.metricType shouldBe "Accuracy"
    }

    @Test
    fun `score は完全一致のみを1件としてカウントする`() {
        val score = rule.score(listOf("Tokyo", "Osaka", "Tokyo"), "Tokyo")

        score shouldBe BigDecimal("0.6667")
    }

    @Test
    fun `score は前後の空白を無視する`() {
        val score = rule.score(listOf("  Tokyo  ", "\tTokyo\n"), "Tokyo")

        score shouldBe BigDecimal("1.0000")
    }

    @Test
    fun `score は大文字小文字をLocale ROOTで無視する`() {
        val score = rule.score(listOf("TOKYO", "tokyo"), "Tokyo")

        score shouldBe BigDecimal("1.0000")
    }

    @Test
    fun `score はUnicode正規化形NFCの表記ゆれを吸収する`() {
        // 合成済み文字列と、そこから導出した分解済み(NFD)文字列は正規化前は
        // equals()がfalseになる別文字列だが、NFC正規化後は一致する。ソースコード上で
        // 両方の文字列を手で書くと編集ツール・エディタ側の正規化に依存してしまうため、
        // 分解形は合成形からNormalizer.Form.NFDで導出する。
        val precomposed = "が" // ひらがな "が"（合成済み）
        val decomposed = Normalizer.normalize(precomposed, Normalizer.Form.NFD) // "か" + 結合濁点
        (precomposed == decomposed) shouldBe false

        val score = rule.score(listOf(decomposed), precomposed)

        score shouldBe BigDecimal("1.0000")
    }

    @Test
    fun `score はexpectedOutputがnullならnullを返す`() {
        rule.score(listOf("Tokyo"), null).shouldBeNull()
    }

    @Test
    fun `score はactualOutputsが空ならnullを返す`() {
        rule.score(emptyList(), "Tokyo").shouldBeNull()
    }

    @Test
    fun `score は1件も一致しなければ0を返す`() {
        val score = rule.score(listOf("Osaka", "Kyoto"), "Tokyo")

        score shouldBe BigDecimal("0.0000")
    }
}
