package promptengine.infrastructure.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Brokerメッセージ本文（封筒JSON）の変換（ADR-0026決定1）。
 * P10aは`payload`単体を本文にしていたが、購読側が`eventType`/`actor`/`traceId`/`occurredAt`を
 * 必要とするため封筒全体へ変更した。その形をここで固定する。
 */
class EventEnvelopeCodecTest {
    private val codec = EventEnvelopeCodec(jacksonObjectMapper())

    private fun outboxEnvelope(payload: String = """{"promptKey":"support/faq"}""") =
        OutboxEnvelope(
            outboxId = UUID.randomUUID(),
            eventId = UUID.randomUUID(),
            eventType = "PromptExecuted",
            aggregateType = "Prompt",
            aggregateId = "support/faq",
            actor = "system",
            traceId = "trace-1",
            payload = payload,
            occurredAt = Instant.parse("2026-08-09T00:00:00Z"),
            attempts = 0,
        )

    @Test
    fun `encodeは封筒の8項目を本文へ載せる`() {
        val source = outboxEnvelope()

        val decoded = codec.decode(codec.encode(source))

        decoded.eventId shouldBe source.eventId
        decoded.eventType shouldBe "PromptExecuted"
        decoded.aggregateType shouldBe "Prompt"
        decoded.aggregateId shouldBe "support/faq"
        decoded.actor shouldBe "system"
        decoded.traceId shouldBe "trace-1"
        decoded.occurredAt shouldBe Instant.parse("2026-08-09T00:00:00Z")
    }

    @Test
    fun `payloadは入れ子のJSONオブジェクトとして埋め込まれ文字列エスケープされない`() {
        val encoded = codec.encode(outboxEnvelope(payload = """{"promptKey":"support/faq"}"""))

        encoded shouldContain """"payload":{"promptKey":"support/faq"}"""
        // 文字列として埋め込まれた場合に現れるエスケープが無いこと。
        encoded shouldNotContain """\"promptKey\""""
    }

    @Test
    fun `decodeしたpayloadはそのままJSON文字列として取り出せる`() {
        val decoded = codec.decode(codec.encode(outboxEnvelope(payload = """{"a":1,"b":"x"}""")))

        jacksonObjectMapper().readTree(decoded.payload).get("b").asText() shouldBe "x"
    }

    @Test
    fun `payloadがJSONとして壊れていても封筒フィールドは中継される`() {
        // 封筒フィールドだけでも監査記録としての価値があるため、中継自体は止めない。
        val encoded = codec.encode(outboxEnvelope(payload = "not-json-at-all"))

        codec.decode(encoded).eventType shouldBe "PromptExecuted"
    }

    @Test
    fun `本文がJSONでなければMalformedEventEnvelopeException`() {
        shouldThrow<MalformedEventEnvelopeException> { codec.decode("<<<not json>>>") }
    }

    @Test
    fun `必須の封筒フィールドが欠けていればMalformedEventEnvelopeException`() {
        shouldThrow<MalformedEventEnvelopeException> {
            codec.decode("""{"eventId":"${UUID.randomUUID()}","eventType":"PromptExecuted"}""")
        }
    }

    @Test
    fun `eventIdがUUIDでなければMalformedEventEnvelopeException`() {
        val broken =
            """
            {"eventId":"not-a-uuid","eventType":"PromptExecuted","aggregateType":"Prompt",
             "aggregateId":"a","actor":"system","traceId":"t","occurredAt":"2026-08-09T00:00:00Z","payload":{}}
            """.trimIndent()

        shouldThrow<MalformedEventEnvelopeException> { codec.decode(broken) }
    }

    @Test
    fun `occurredAtがISO-8601でなければMalformedEventEnvelopeException`() {
        val broken =
            """
            {"eventId":"${UUID.randomUUID()}","eventType":"PromptExecuted","aggregateType":"Prompt",
             "aggregateId":"a","actor":"system","traceId":"t","occurredAt":"yesterday","payload":{}}
            """.trimIndent()

        shouldThrow<MalformedEventEnvelopeException> { codec.decode(broken) }
    }

    @Test
    fun `payloadが欠けていても空オブジェクトとしてデコードできる`() {
        val noPayload =
            """
            {"eventId":"${UUID.randomUUID()}","eventType":"PromptExecuted","aggregateType":"Prompt",
             "aggregateId":"a","actor":"system","traceId":"t","occurredAt":"2026-08-09T00:00:00Z"}
            """.trimIndent()

        codec.decode(noPayload).payload shouldBe "{}"
    }

    @Test
    fun `封筒フィールドが文字列でなければMalformedEventEnvelopeException`() {
        val broken =
            """
            {"eventId":"${UUID.randomUUID()}","eventType":123,"aggregateType":"Prompt",
             "aggregateId":"a","actor":"system","traceId":"t","occurredAt":"2026-08-09T00:00:00Z","payload":{}}
            """.trimIndent()

        shouldThrow<MalformedEventEnvelopeException> { codec.decode(broken) }
    }
}
