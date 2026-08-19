package promptengine.engine.benchmark

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [ConsistencyScoringRule]（Accuracyとは異なりexpectedOutputを使わない、ADR-0035決定5）のテスト。
 */
class ConsistencyScoringRuleTest {
    private val rule = ConsistencyScoringRule()

    @Test
    fun `metricTypeはConsistency`() {
        rule.metricType shouldBe "Consistency"
    }

    @Test
    fun `全出力が一致すればスコアは1_0`() {
        val score = rule.score(listOf("answer", "answer", "answer"), expectedOutput = null)

        score shouldBe BigDecimal("1.0000")
    }

    @Test
    fun `出力がばらつけば最頻値の割合になる`() {
        val score = rule.score(listOf("a", "a", "b"), expectedOutput = null)

        score shouldBe BigDecimal("0.6667")
    }

    @Test
    fun `全出力が異なれば最頻値は1件のみで割合は1_n`() {
        val score = rule.score(listOf("a", "b", "c", "d"), expectedOutput = null)

        score shouldBe BigDecimal("0.2500")
    }

    @Test
    fun `正規化規則を適用する 大文字小文字と前後空白を無視する`() {
        val score = rule.score(listOf("Answer", " answer ", "ANSWER"), expectedOutput = null)

        score shouldBe BigDecimal("1.0000")
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

    @Test
    fun `最頻値が同数で複数存在する場合は正規化済み文字列の自然順序で最小のものを採用する`() {
        // "a"と"b"がともに1回ずつ（同率首位）。実行順やMap反復順に依存させず、常に同じ結果になる。
        val score = rule.score(listOf("b", "a"), expectedOutput = null)

        score shouldBe BigDecimal("0.5000")
    }
}
