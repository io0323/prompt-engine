package promptengine.engine.benchmark

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [DeterminismScoringRule]（正規化なし・バイト完全一致、ADR-0035決定5）のテスト。
 */
class DeterminismScoringRuleTest {
    private val rule = DeterminismScoringRule()

    @Test
    fun `metricTypeはDeterminism`() {
        rule.metricType shouldBe "Determinism"
    }

    @Test
    fun `全出力が最初の出力とバイト完全一致すればスコアは1_0`() {
        val score = rule.score(listOf("answer", "answer", "answer"), expectedOutput = null)

        score shouldBe BigDecimal("1.0000")
    }

    @Test
    fun `出力がばらつけば最初の出力と一致する件数の割合になる`() {
        val score = rule.score(listOf("a", "a", "b"), expectedOutput = null)

        score shouldBe BigDecimal("0.6667")
    }

    @Test
    fun `最初の出力と異なる出力しかなければスコアは1_n`() {
        val score = rule.score(listOf("a", "b", "b", "b"), expectedOutput = null)

        score shouldBe BigDecimal("0.2500")
    }

    @Test
    fun `Accuracy Consistencyと異なり正規化しない 大文字小文字の違いは不一致として扱う`() {
        val score = rule.score(listOf("Answer", "answer"), expectedOutput = null)

        score shouldBe BigDecimal("0.5000")
    }

    @Test
    fun `Accuracy Consistencyと異なり正規化しない 前後空白の違いは不一致として扱う`() {
        val score = rule.score(listOf("answer", "answer "), expectedOutput = null)

        score shouldBe BigDecimal("0.5000")
    }

    @Test
    fun `expectedOutputは無視する`() {
        val withExpected = rule.score(listOf("a", "a"), expectedOutput = "unrelated")
        val withoutExpected = rule.score(listOf("a", "a"), expectedOutput = null)

        withExpected shouldBe withoutExpected
    }

    @Test
    fun `actualOutputsが空ならnullを返す`() {
        rule.score(emptyList(), expectedOutput = null) shouldBe null
    }
}
