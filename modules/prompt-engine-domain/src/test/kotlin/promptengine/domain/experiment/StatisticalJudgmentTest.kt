package promptengine.domain.experiment

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [StatisticalJudgment]の直接テスト（ADR-0034決定5）。
 *
 * 構築自体は`prompt-engine-core`の`PromotionServiceImpl`が行うが、CLAUDE.md「domain の型は
 * domain モジュール内のテストで直接検証する。core など他モジュールのテスト経由で間接的に
 * 実行されるだけの状態にしない」に従い、本クラス自身もdomainモジュール内で直接検証する。
 */
class StatisticalJudgmentTest {
    @Test
    fun `全フィールドをそのまま保持する`() {
        val judgment =
            StatisticalJudgment(
                controlSampleSize = 30,
                challengerSampleSize = 32,
                controlMean = BigDecimal("100.5"),
                challengerMean = BigDecimal("120.0"),
                pValue = 0.01,
                sufficientSample = true,
                significant = true,
            )

        judgment.controlSampleSize shouldBe 30
        judgment.challengerSampleSize shouldBe 32
        judgment.controlMean shouldBe BigDecimal("100.5")
        judgment.challengerMean shouldBe BigDecimal("120.0")
        judgment.pValue shouldBe 0.01
        judgment.sufficientSample shouldBe true
        judgment.significant shouldBe true
    }

    @Test
    fun `サンプル不足時はpValueと平均がnullになりうる`() {
        val judgment =
            StatisticalJudgment(
                controlSampleSize = 3,
                challengerSampleSize = 0,
                controlMean = null,
                challengerMean = null,
                pValue = null,
                sufficientSample = false,
                significant = false,
            )

        judgment.pValue shouldBe null
        judgment.controlMean shouldBe null
        judgment.challengerMean shouldBe null
        judgment.sufficientSample shouldBe false
    }
}
