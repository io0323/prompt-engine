package promptengine.engine.experiment

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.experiment.PromotionService
import java.math.BigDecimal

/**
 * [PromotionServiceImpl]のテスト（Welch's t-test、ADR-0034決定5）。
 */
class PromotionServiceImplTest {
    private val service = PromotionServiceImpl()

    private fun decimals(vararg values: Int): List<BigDecimal> = values.map { BigDecimal(it) }

    @Test
    fun `サンプル数が最小未満なら判定不能を返す`() {
        val control = decimals(*IntArray(PromotionService.MIN_SAMPLE_SIZE - 1) { 100 })
        val challenger = decimals(*IntArray(PromotionService.MIN_SAMPLE_SIZE) { 100 })

        val result = service.judge(control, challenger)

        result.sufficientSample shouldBe false
        result.pValue shouldBe null
        result.significant shouldBe false
    }

    @Test
    fun `両群が同じ分布なら有意差なしと判定する`() {
        val control = decimals(*IntArray(50) { 100 })
        val challenger = decimals(*IntArray(50) { 100 })

        val result = service.judge(control, challenger)

        result.sufficientSample shouldBe true
        result.significant shouldBe false
    }

    @Test
    fun `明確に分布が異なれば有意差ありと判定する`() {
        val control = (1..50).map { BigDecimal(100 + it % 3) }
        val challenger = (1..50).map { BigDecimal(500 + it % 3) }

        val result = service.judge(control, challenger)

        result.sufficientSample shouldBe true
        result.significant shouldBe true
        (result.pValue!! < PromotionService.SIGNIFICANCE_LEVEL) shouldBe true
    }

    @Test
    fun `平均値をそのまま返す`() {
        val control = decimals(10, 20, 30) + decimals(*IntArray(PromotionService.MIN_SAMPLE_SIZE - 3) { 20 })
        val challenger = decimals(*IntArray(PromotionService.MIN_SAMPLE_SIZE) { 40 })

        val result = service.judge(control, challenger)

        result.challengerMean shouldBe BigDecimal(40).setScale(result.challengerMean!!.scale())
    }
}
