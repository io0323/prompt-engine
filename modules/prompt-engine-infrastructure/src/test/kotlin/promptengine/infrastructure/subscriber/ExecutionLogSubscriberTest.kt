package promptengine.infrastructure.subscriber

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.event.EventTopic
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** `PromptExecuted` → `execution_logs`（設計書§12、ADR-0026決定1）。 */
class ExecutionLogSubscriberTest {
    private val payloadCodec = PromptExecutedPayloadCodec(testObjectMapper)

    private fun subscriber(repository: RecordingExecutionLogRepository) =
        ExecutionLogSubscriber(repository, payloadCodec)

    @Test
    fun `pe_executionトピックのみを購読する`() {
        subscriber(RecordingExecutionLogRepository()).topics shouldBe setOf(EventTopic.PE_EXECUTION)
    }

    @Test
    fun `PromptExecutedのpayloadをexecution_logsの1行へ写す`() {
        val repository = RecordingExecutionLogRepository()
        val eventId = UUID.randomUUID()

        subscriber(repository).handle(
            envelope(
                eventId = eventId,
                traceId = "trace-42",
                occurredAt = Instant.parse("2026-08-09T12:00:00Z"),
                payload = promptExecutedPayload(major = 1, minor = 2, patch = 3),
            ),
        )

        val entry = repository.appended.single()
        entry.eventId shouldBe eventId
        entry.promptKey shouldBe "support/faq"
        entry.semVer shouldBe SemVer(1, 2, 3)
        entry.traceId shouldBe "trace-42"
        entry.latency shouldBe LatencyMs(250)
        entry.usage.inputTokens.value shouldBe 800
        entry.usage.outputTokens.value shouldBe 200
        entry.status shouldBe ExecutionStatus.SUCCESS
        entry.executedAt shouldBe Instant.parse("2026-08-09T12:00:00Z")
    }

    @Test
    fun `costは合計トークン数とModelProfile単価の積`() {
        val repository = RecordingExecutionLogRepository()

        subscriber(repository).handle(
            envelope(payload = promptExecutedPayload(inputTokens = 800, outputTokens = 200, costPerToken = "0.0004")),
        )

        // BigDecimalのscaleは契約ではない（DBのDECIMAL列は値のみを保持する）ため数値比較する。
        repository.appended.single().cost.value.compareTo(BigDecimal("0.4")) shouldBe 0
    }

    /** `caller_system`の実データ源がM1に無いため`actor`で代替する（ADR-0026「既知の限界」）。 */
    @Test
    fun `caller_systemには封筒のactorを暫定的に使う`() {
        val repository = RecordingExecutionLogRepository()

        subscriber(repository).handle(envelope(actor = "user:alice"))

        repository.appended.single().callerSystem shouldBe "user:alice"
    }

    @Test
    fun `pe_executionの他イベントは無視する`() {
        val repository = RecordingExecutionLogRepository()
        val ignored = listOf("PromptExecutionFailed", "ResponseParsed", "ResponseParseFailed")

        ignored.forEach { eventType ->
            subscriber(repository).handle(envelope(eventType = eventType, payload = """{"any":"thing"}"""))
        }

        repository.appended shouldBe emptyList()
    }

    @Test
    fun `payloadが壊れていれば例外を伝播させる（駆動側がDLQへ退避する）`() {
        val repository = RecordingExecutionLogRepository()

        shouldThrow<MalformedPromptExecutedPayloadException> {
            subscriber(repository).handle(envelope(payload = """{"promptKey":"support/faq"}"""))
        }
    }

    @Test
    fun `書き込みに失敗した場合は例外を伝播させる`() {
        val repository = RecordingExecutionLogRepository(failWith = IllegalStateException("db down"))

        shouldThrow<IllegalStateException> { subscriber(repository).handle(envelope()) }
    }

    // ---- temperature（ADR-0035決定5）: renderHashから除外する分、実行記録側に残す ----

    @Test
    fun `temperatureを含むpayloadはexecution_logsの1行へそのまま写す`() {
        val repository = RecordingExecutionLogRepository()

        subscriber(repository).handle(envelope(payload = promptExecutedPayload(temperature = 0.0)))

        repository.appended.single().temperature shouldBe 0.0
    }

    @Test
    fun `temperatureを含まないpayloadはnullのまま写す`() {
        val repository = RecordingExecutionLogRepository()

        subscriber(repository).handle(envelope(payload = promptExecutedPayload(temperature = null)))

        repository.appended.single().temperature shouldBe null
    }
}
