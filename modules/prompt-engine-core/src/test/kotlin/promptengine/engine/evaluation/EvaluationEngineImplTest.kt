package promptengine.engine.evaluation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.evaluation.EvaluationRule
import promptengine.domain.evaluation.EvaluationRuleFailureHandler
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.evaluation.PromptExecutionSummary
import promptengine.domain.execution.Usage
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class EvaluationEngineImplTest {
    private val fixedInstant: Instant = Instant.parse("2026-08-09T00:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private class RecordingFailureHandler : EvaluationRuleFailureHandler {
        val handled = mutableListOf<Pair<String, String>>()

        override fun handle(
            metricType: String,
            execution: PromptExecutionSummary,
            cause: Throwable,
        ) {
            handled += metricType to cause.javaClass.simpleName
        }
    }

    private class StubRule(
        override val metricType: String,
        private val score: BigDecimal?,
        private val failWith: Throwable? = null,
    ) : EvaluationRule {
        override val method: String = "stub"

        override fun evaluate(execution: PromptExecutionSummary): BigDecimal? {
            failWith?.let { throw it }
            return score
        }
    }

    private fun summary() =
        PromptExecutionSummary(
            eventId = UUID.randomUUID(),
            promptKey = "support/faq",
            semVer = SemVer(1, 0, 0),
            latency = LatencyMs(250),
            usage = Usage(TokenCount(800), TokenCount(200)),
            costPerToken = Cost(BigDecimal("0.0004")),
            status = ExecutionStatus.SUCCESS,
            retryCount = 0,
            callerSystem = "system",
            traceId = "trace-1",
            occurredAt = Instant.EPOCH,
        )

    @Test
    fun `M1の3評価器を登録すると1実行あたり3件のEvaluationRecordを返す`() {
        val engine =
            EvaluationEngineImpl(
                listOf(LatencyEvaluationRule(), TokenUsageEvaluationRule(), CostEvaluationRule()),
                RecordingFailureHandler(),
                clock,
            )

        val records = engine.evaluate(summary())

        records.map { it.metricType } shouldContainExactly listOf("Latency", "TokenUsage", "Cost")
        records.map { it.score } shouldContainExactly
            listOf(BigDecimal.valueOf(250L), BigDecimal.valueOf(1000L), BigDecimal("0.4000"))
    }

    @Test
    fun `EvaluationRecordにはイベントのeventId promptKey semVerとClockの時刻が転記される`() {
        val engine = EvaluationEngineImpl(listOf(LatencyEvaluationRule()), RecordingFailureHandler(), clock)
        val execution = summary()

        val record = engine.evaluate(execution).single()

        record.eventId shouldBe execution.eventId
        record.promptKey shouldBe "support/faq"
        record.semVer shouldBe SemVer(1, 0, 0)
        record.evaluatedAt shouldBe fixedInstant
        record.sampleRef shouldBe "trace-1"
    }

    @Test
    fun `nullを返した評価器の指標は行を書かない（算出不能とスコア0を区別する）`() {
        val engine =
            EvaluationEngineImpl(
                listOf(StubRule("Quality", score = null), LatencyEvaluationRule()),
                RecordingFailureHandler(),
                clock,
            )

        engine.evaluate(summary()).map { it.metricType } shouldContainExactly listOf("Latency")
    }

    @Test
    fun `1つの評価器が例外を投げても他の評価器の結果は失われない`() {
        val failureHandler = RecordingFailureHandler()
        val engine =
            EvaluationEngineImpl(
                listOf(
                    StubRule("Broken", score = null, failWith = IllegalStateException("boom")),
                    LatencyEvaluationRule(),
                ),
                failureHandler,
                clock,
            )

        val records = engine.evaluate(summary())

        records.map { it.metricType } shouldContainExactly listOf("Latency")
        failureHandler.handled shouldContainExactly listOf("Broken" to "IllegalStateException")
    }

    @Test
    fun `failureHandler自体が例外を投げても評価全体は継続する`() {
        val throwingHandler =
            object : EvaluationRuleFailureHandler {
                override fun handle(
                    metricType: String,
                    execution: PromptExecutionSummary,
                    cause: Throwable,
                ): Unit = error("handler boom")
            }
        val engine =
            EvaluationEngineImpl(
                listOf(
                    StubRule("Broken", score = null, failWith = IllegalStateException("boom")),
                    LatencyEvaluationRule(),
                ),
                throwingHandler,
                clock,
            )

        engine.evaluate(summary()).map { it.metricType } shouldContainExactly listOf("Latency")
    }

    @Test
    fun `metricTypeが重複する評価器の登録は起動時に失敗する`() {
        shouldThrow<IllegalArgumentException> {
            EvaluationEngineImpl(
                listOf(LatencyEvaluationRule(), LatencyEvaluationRule()),
                RecordingFailureHandler(),
                clock,
            )
        }
    }

    @Test
    fun `評価器が空なら結果も空（購読自体は成立する）`() {
        EvaluationEngineImpl(emptyList(), RecordingFailureHandler(), clock).evaluate(summary()) shouldBe emptyList()
    }

    @Test
    fun `構築後に呼出元がリストを変更しても登録済み評価器は影響を受けない`() {
        val mutableRules = mutableListOf<EvaluationRule>(LatencyEvaluationRule())
        val engine = EvaluationEngineImpl(mutableRules, RecordingFailureHandler(), clock)

        mutableRules += CostEvaluationRule()

        engine.evaluate(summary()).map { it.metricType } shouldContainExactly listOf("Latency")
    }
}
