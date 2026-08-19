package promptengine.infrastructure.subscriber

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.time.Instant

/** `PromptExecuted`のpayload → [promptengine.domain.evaluation.PromptExecutionSummary]（ADR-0026決定3）。 */
class PromptExecutedPayloadCodecTest {
    private val codec = PromptExecutedPayloadCodec(testObjectMapper)

    @Test
    fun `payloadの全フィールドを復元する`() {
        val source = envelope(payload = promptExecutedPayload(major = 2, minor = 3, patch = 4, retryCount = 1))

        val summary = codec.decode(source)

        summary.eventId shouldBe source.eventId
        summary.promptKey shouldBe "support/faq"
        summary.semVer shouldBe SemVer(2, 3, 4)
        summary.latency shouldBe LatencyMs(250)
        summary.usage.inputTokens.value shouldBe 800
        summary.usage.outputTokens.value shouldBe 200
        summary.retryCount shouldBe 1
        summary.status shouldBe ExecutionStatus.SUCCESS
        summary.totalTokens shouldBe 1000
    }

    @Test
    fun `costPerTokenは数値として復元する`() {
        val summary = codec.decode(envelope(payload = promptExecutedPayload(costPerToken = "0.0004")))

        summary.costPerToken.value.compareTo(BigDecimal("0.0004")) shouldBe 0
    }

    /** 封筒側の項目（`actor`/`traceId`/`occurredAt`）は payload ではなく封筒から取る。 */
    @Test
    fun `callerSystemとtraceIdとoccurredAtは封筒から取る`() {
        val source =
            envelope(
                actor = "user:alice",
                traceId = "trace-99",
                occurredAt = Instant.parse("2026-08-09T15:00:00Z"),
            )

        val summary = codec.decode(source)

        summary.callerSystem shouldBe "user:alice"
        summary.traceId shouldBe "trace-99"
        summary.occurredAt shouldBe Instant.parse("2026-08-09T15:00:00Z")
    }

    @Test
    fun `payloadがJSONでなければ失敗する`() {
        shouldThrow<MalformedPromptExecutedPayloadException> { codec.decode(envelope(payload = "<<<not json>>>")) }
    }

    @Test
    fun `必須フィールドが欠けていれば失敗する`() {
        val required =
            listOf("promptKey", "inputTokens", "outputTokens", "retryCount", "latencyMs", "costPerToken", "status")

        required.forEach { field ->
            val broken =
                testObjectMapper.readTree(
                    promptExecutedPayload(),
                ) as com.fasterxml.jackson.databind.node.ObjectNode
            broken.remove(field)

            withClue("missing field=$field") {
                shouldThrow<MalformedPromptExecutedPayloadException> {
                    codec.decode(envelope(payload = testObjectMapper.writeValueAsString(broken)))
                }
            }
        }
    }

    @Test
    fun `semVerがオブジェクトでなければ失敗する`() {
        val broken =
            """
            {"promptKey":"support/faq","semVer":"1.0.0","inputTokens":1,"outputTokens":1,
             "retryCount":0,"latencyMs":1,"costPerToken":0,"status":"SUCCESS"}
            """.trimIndent()

        shouldThrow<MalformedPromptExecutedPayloadException> { codec.decode(envelope(payload = broken)) }
    }

    @Test
    fun `未知のstatusは失敗させる（既定値で埋めない）`() {
        shouldThrow<MalformedPromptExecutedPayloadException> {
            codec.decode(envelope(payload = promptExecutedPayload(status = "MAYBE")))
        }
    }

    @Test
    fun `promptKeyが数値なら失敗する`() {
        val broken =
            """
            {"promptKey":123,"semVer":{"major":1,"minor":0,"patch":0},"inputTokens":1,"outputTokens":1,
             "retryCount":0,"latencyMs":1,"costPerToken":0,"status":"SUCCESS"}
            """.trimIndent()

        shouldThrow<MalformedPromptExecutedPayloadException> { codec.decode(envelope(payload = broken)) }
    }

    @Test
    fun `数値フィールドが文字列なら失敗する`() {
        val numericFields = listOf("inputTokens", "outputTokens", "retryCount", "latencyMs", "costPerToken")

        numericFields.forEach { field ->
            val broken =
                testObjectMapper.readTree(
                    promptExecutedPayload(),
                ) as com.fasterxml.jackson.databind.node.ObjectNode
            broken.put(field, "not-a-number")

            withClue("non-numeric field=$field") {
                shouldThrow<MalformedPromptExecutedPayloadException> {
                    codec.decode(envelope(payload = testObjectMapper.writeValueAsString(broken)))
                }
            }
        }
    }

    @Test
    fun `semVerの構成要素が数値でなければ失敗する`() {
        val broken =
            """
            {"promptKey":"support/faq","semVer":{"major":"x","minor":0,"patch":0},"inputTokens":1,
             "outputTokens":1,"retryCount":0,"latencyMs":1,"costPerToken":0,"status":"SUCCESS"}
            """.trimIndent()

        shouldThrow<MalformedPromptExecutedPayloadException> { codec.decode(envelope(payload = broken)) }
    }

    @Test
    fun `FAILEDステータスも復元できる`() {
        codec.decode(envelope(payload = promptExecutedPayload(status = "FAILED"))).status shouldBe
            ExecutionStatus.FAILED
    }

    // ---- temperature（ADR-0035決定5、M2-4bフェーズ(c)） ----

    @Test
    fun `temperatureを含むpayloadは値を復元する`() {
        val summary = codec.decode(envelope(payload = promptExecutedPayload(temperature = 0.7)))

        summary.temperature shouldBe 0.7
    }

    @Test
    fun `temperatureが0_0でも復元する`() {
        val summary = codec.decode(envelope(payload = promptExecutedPayload(temperature = 0.0)))

        summary.temperature shouldBe 0.0
    }

    @Test
    fun `temperatureを含まない既存イベントのpayloadでも壊れずnullを返す`() {
        val summary = codec.decode(envelope(payload = promptExecutedPayload(temperature = null)))

        summary.temperature shouldBe null
    }
}
