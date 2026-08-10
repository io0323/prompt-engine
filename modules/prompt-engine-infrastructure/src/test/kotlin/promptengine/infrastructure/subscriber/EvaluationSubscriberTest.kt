package promptengine.infrastructure.subscriber

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import promptengine.domain.evaluation.EvaluationEngine
import promptengine.domain.evaluation.EvaluationRecord
import promptengine.domain.evaluation.PromptEvaluationCompletedEvent
import promptengine.domain.evaluation.PromptExecutionSummary
import promptengine.domain.event.EventTopic
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** `PromptExecuted` → 評価 → `evaluation_records` → `PromptEvaluationCompleted`（ADR-0026決定3）。 */
class EvaluationSubscriberTest {
    private val payloadCodec = PromptExecutedPayloadCodec(testObjectMapper)
    private val fixedInstant: Instant = Instant.parse("2026-08-09T09:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    /** 与えられた指標をそのまま返すだけのEngine（実算出は`prompt-engine-core`のテストが担う）。 */
    private class StubEvaluationEngine(private val metrics: List<Pair<String, String>>) : EvaluationEngine {
        var invocations = 0

        override fun evaluate(execution: PromptExecutionSummary): List<EvaluationRecord> {
            invocations++
            return metrics.map { (metricType, score) ->
                EvaluationRecord(
                    eventId = execution.eventId,
                    promptKey = execution.promptKey,
                    semVer = execution.semVer,
                    metricType = metricType,
                    score = BigDecimal(score),
                    method = "stub",
                    sampleRef = execution.traceId,
                    evaluatedAt = Instant.EPOCH,
                )
            }
        }
    }

    private fun subscriber(
        engine: EvaluationEngine,
        repository: RecordingEvaluationRepository,
        eventBus: RecordingEventBusAdapter,
    ) = EvaluationSubscriber(engine, repository, eventBus, payloadCodec, clock)

    @Test
    fun `pe_executionトピックのみを購読する`() {
        val subscriber =
            subscriber(StubEvaluationEngine(emptyList()), RecordingEvaluationRepository(), RecordingEventBusAdapter())

        subscriber.topics shouldBe setOf(EventTopic.PE_EXECUTION)
    }

    @Test
    fun `評価結果を保存しPromptEvaluationCompletedを発行する`() {
        val repository = RecordingEvaluationRepository()
        val eventBus = RecordingEventBusAdapter()
        val engine = StubEvaluationEngine(listOf("Latency" to "250", "Cost" to "0.4"))
        val source = envelope(payload = promptExecutedPayload(major = 2, minor = 1, patch = 0))

        subscriber(engine, repository, eventBus).handle(source)

        repository.saved.map { it.metricType } shouldContainExactly listOf("Latency", "Cost")

        val published = eventBus.published.single().shouldBeInstanceOf<PromptEvaluationCompletedEvent>()
        published.eventType shouldBe "PromptEvaluationCompleted"
        published.traceId shouldBe source.traceId
        published.occurredAt shouldBe fixedInstant
        published.payload.promptKey shouldBe "support/faq"
        published.payload.semVer shouldBe SemVer(2, 1, 0)
        published.payload.sourceEventId shouldBe source.eventId
        published.payload.metrics.map { it.metricType } shouldContainExactly listOf("Latency", "Cost")
    }

    @Test
    fun `保存が0件（全て重複＝再配信）なら完了イベントを再発行しない`() {
        val repository = RecordingEvaluationRepository(insertedCount = 0)
        val eventBus = RecordingEventBusAdapter()
        val engine = StubEvaluationEngine(listOf("Latency" to "250"))

        subscriber(engine, repository, eventBus).handle(envelope())

        eventBus.published shouldBe emptyList()
    }

    @Test
    fun `評価器が1件も値を出さなければ保存も発行もしない`() {
        val repository = RecordingEvaluationRepository()
        val eventBus = RecordingEventBusAdapter()

        subscriber(StubEvaluationEngine(emptyList()), repository, eventBus).handle(envelope())

        repository.saved shouldBe emptyList()
        eventBus.published shouldBe emptyList()
    }

    @Test
    fun `pe_executionの他イベントでは評価器を呼ばない`() {
        val engine = StubEvaluationEngine(listOf("Latency" to "250"))
        val eventBus = RecordingEventBusAdapter()

        subscriber(engine, RecordingEvaluationRepository(), eventBus).handle(
            envelope(eventType = "ResponseParsed", payload = """{"any":"thing"}"""),
        )

        engine.invocations shouldBe 0
        eventBus.published shouldBe emptyList()
    }
}
