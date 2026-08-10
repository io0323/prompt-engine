package promptengine.infrastructure.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import promptengine.domain.event.EventEnvelope
import java.time.Instant
import java.util.UUID

/**
 * Broker上を流れるメッセージ本文（封筒JSON）のエンコード／デコード（ADR-0026決定1）。
 *
 * ## P10aからのワイヤ形式の変更
 * P10aの[OutboxRelayer]はメッセージ本文として`payload`のJSONだけを送っていた。当時の
 * 購読側はテスト専用fixture（`EventBusOutboxIdempotentConsumerIntegrationTest`）のみで、
 * `event_id`さえヘッダから読めれば冪等性パターンを実証できたため十分だった。
 *
 * P10bの実購読側は`payload`だけでは成立しない。`AuditEngine`は`audit_logs`へ
 * `aggregate_type`/`action`（＝`eventType`）/`actor`/`trace_id`/`occurred_at`を書く必要があり、
 * `ExecutionLogSubscriber`も`trace_id`を要る。これらは[promptengine.domain.event.DomainEvent]の
 * 封筒フィールドであって`payload`には含まれない。
 *
 * このため本文を**封筒全体のJSON**へ変更する（設計書§14がイベントを封筒として定義している
 * のに沿う形。ADR-0026決定1）。実購読側がまだ存在しない段階での変更であり、互換性を
 * 気にする既存コンシューマは無い。`event-id`ヘッダ（ADR-0025決定7）は引き続き載せる。
 *
 * [encode]では`payload`を**入れ子のJSONオブジェクトとして**埋め込む（文字列としてエスケープ
 * しない）。文字列にすると購読側が二重にパースする必要があり、Brokerのメッセージを人が
 * 読む際にも読みにくくなるため。
 */
class EventEnvelopeCodec(
    private val objectMapper: ObjectMapper,
) {
    /** [envelope]（中継元のOutbox行）を、Brokerへ送るメッセージ本文JSONへ変換する。 */
    fun encode(envelope: OutboxEnvelope): String {
        val node = objectMapper.createObjectNode()
        node.put(FIELD_EVENT_ID, envelope.eventId.toString())
        node.put(FIELD_EVENT_TYPE, envelope.eventType)
        node.put(FIELD_AGGREGATE_TYPE, envelope.aggregateType)
        node.put(FIELD_AGGREGATE_ID, envelope.aggregateId)
        node.put(FIELD_ACTOR, envelope.actor)
        node.put(FIELD_TRACE_ID, envelope.traceId)
        node.put(FIELD_OCCURRED_AT, envelope.occurredAt.toString())
        node.set<JsonNode>(FIELD_PAYLOAD, readPayload(envelope.payload))
        return objectMapper.writeValueAsString(node)
    }

    /**
     * Brokerから受け取ったメッセージ本文JSONを[EventEnvelope]へ復元する。
     * 封筒として解釈できない本文は[MalformedEventEnvelopeException]で失敗させる
     * （購読側の駆動が捕捉してDLQへ退避する。静かに読み飛ばさない）。
     */
    fun decode(json: String): EventEnvelope {
        val node =
            runCatching { objectMapper.readTree(json) }.getOrNull()
                ?: throw MalformedEventEnvelopeException("message body is not valid JSON")
        return EventEnvelope(
            eventId = requiredUuid(node, FIELD_EVENT_ID),
            eventType = requiredText(node, FIELD_EVENT_TYPE),
            aggregateType = requiredText(node, FIELD_AGGREGATE_TYPE),
            aggregateId = requiredText(node, FIELD_AGGREGATE_ID),
            actor = requiredText(node, FIELD_ACTOR),
            traceId = requiredText(node, FIELD_TRACE_ID),
            payload = objectMapper.writeValueAsString(node.get(FIELD_PAYLOAD) ?: objectMapper.createObjectNode()),
            occurredAt = requiredInstant(node, FIELD_OCCURRED_AT),
        )
    }

    /**
     * DB上の`payload`列はJSON文字列。入れ子オブジェクトとして埋め込むためツリーへ戻す。
     * 解釈できない場合は空オブジェクトにフォールバックする（中継そのものを止めない。
     * 封筒フィールドだけでも監査記録としての価値があるため）。
     */
    private fun readPayload(payload: String): JsonNode =
        runCatching { objectMapper.readTree(payload) }.getOrNull() ?: objectMapper.createObjectNode()

    private fun requiredText(
        node: JsonNode,
        field: String,
    ): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()
            ?: throw MalformedEventEnvelopeException("missing or non-textual envelope field '$field'")

    private fun requiredUuid(
        node: JsonNode,
        field: String,
    ): UUID =
        runCatching { UUID.fromString(requiredText(node, field)) }.getOrElse {
            throw MalformedEventEnvelopeException("envelope field '$field' is not a UUID")
        }

    private fun requiredInstant(
        node: JsonNode,
        field: String,
    ): Instant =
        runCatching { Instant.parse(requiredText(node, field)) }.getOrElse {
            throw MalformedEventEnvelopeException("envelope field '$field' is not an ISO-8601 instant")
        }

    private companion object {
        const val FIELD_EVENT_ID = "eventId"
        const val FIELD_EVENT_TYPE = "eventType"
        const val FIELD_AGGREGATE_TYPE = "aggregateType"
        const val FIELD_AGGREGATE_ID = "aggregateId"
        const val FIELD_ACTOR = "actor"
        const val FIELD_TRACE_ID = "traceId"
        const val FIELD_OCCURRED_AT = "occurredAt"
        const val FIELD_PAYLOAD = "payload"
    }
}

/** Brokerから受け取った本文を[EventEnvelope]として解釈できなかった。 */
class MalformedEventEnvelopeException(message: String) : IllegalArgumentException(message)
