package promptengine.engine.evaluation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.evaluation.PromptExecutionSummary
import promptengine.domain.execution.Usage
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** M1の3評価器（設計書§2.12 Latency / Token Usage / Cost）の算出仕様を固定する。 */
class EvaluationRuleTest {
    private fun summary(
        latencyMs: Long = 250,
        inputTokens: Int = 800,
        outputTokens: Int = 200,
        costPerToken: String = "0.0004",
    ) = PromptExecutionSummary(
        eventId = UUID.randomUUID(),
        promptKey = "support/faq",
        semVer = SemVer(1, 0, 0),
        latency = LatencyMs(latencyMs),
        usage = Usage(TokenCount(inputTokens), TokenCount(outputTokens)),
        costPerToken = Cost(BigDecimal(costPerToken)),
        status = ExecutionStatus.SUCCESS,
        retryCount = 0,
        callerSystem = "system",
        traceId = "trace-1",
        occurredAt = Instant.EPOCH,
    )

    @Test
    fun `LatencyはExecution Stageの実測ミリ秒をそのままスコアにする`() {
        val rule = LatencyEvaluationRule()

        rule.metricType shouldBe "Latency"
        rule.method shouldBe "execution-stage-measured"
        rule.evaluate(summary(latencyMs = 250)) shouldBe BigDecimal.valueOf(250L)
    }

    @Test
    fun `TokenUsageは入力と出力のトークン合計をスコアにする`() {
        val rule = TokenUsageEvaluationRule()

        rule.metricType shouldBe "TokenUsage"
        rule.method shouldBe "provider-usage"
        rule.evaluate(summary(inputTokens = 800, outputTokens = 200)) shouldBe BigDecimal.valueOf(1000L)
    }

    @Test
    fun `Costは合計トークン数とModelProfile単価の積をスコアにする`() {
        val rule = CostEvaluationRule()

        rule.metricType shouldBe "Cost"
        rule.method shouldBe "usage-x-model-profile-rate"
        // (800 + 200) * 0.0004 = 0.4000（BigDecimalのscaleは乗算結果のscaleをそのまま保持する）
        rule.evaluate(summary(inputTokens = 800, outputTokens = 200, costPerToken = "0.0004")) shouldBe
            BigDecimal("0.4000")
    }

    @Test
    fun `単価0のModelProfileではCostは0になる`() {
        CostEvaluationRule().evaluate(summary(costPerToken = "0")) shouldBe BigDecimal("0")
    }

    @Test
    fun `トークン数0でもTokenUsageとCostは0として算出できる`() {
        val zeroTokens = summary(inputTokens = 0, outputTokens = 0)

        TokenUsageEvaluationRule().evaluate(zeroTokens) shouldBe BigDecimal.ZERO
        CostEvaluationRule().evaluate(zeroTokens) shouldBe BigDecimal("0.0000")
    }
}
