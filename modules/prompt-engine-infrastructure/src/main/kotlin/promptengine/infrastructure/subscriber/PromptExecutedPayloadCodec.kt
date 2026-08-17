package promptengine.infrastructure.subscriber

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.evaluation.PromptExecutionSummary
import promptengine.domain.event.EventEnvelope
import promptengine.domain.execution.Usage
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.util.UUID

/**
 * `PromptExecuted`（設計書§14）の`payload`を[PromptExecutionSummary]へ復元する
 * （ADR-0026決定3）。`ExecutionLogSubscriber`と`EvaluationSubscriber`が共有する。
 *
 * `semVer`は[promptengine.domain.shared.SemVer]（`{major, minor, patch}`）としてシリアライズ
 * されているため、そのままオブジェクトとして読む。
 *
 * `callerSystem`は`execution_logs.caller_system`（NOT NULL、設計書§12）へ入れる値だが、
 * M1時点でPipelineへ呼出元識別情報を伝搬する経路が存在しない。イベント封筒の`actor`を
 * 暫定的に使う（ADR-0026「既知の限界」）。
 *
 * 解釈できないpayloadは[MalformedPromptExecutedPayloadException]で失敗させる
 * （[KafkaSubscriberRunner]が捕捉してDLQへ退避する。壊れたイベントを既定値で
 * 埋めて「正常に記録された」ことにしない）。
 */
class PromptExecutedPayloadCodec(
    private val objectMapper: ObjectMapper,
) {
    fun decode(envelope: EventEnvelope): PromptExecutionSummary {
        val node =
            runCatching { objectMapper.readTree(envelope.payload) }.getOrNull()
                ?: throw MalformedPromptExecutedPayloadException("payload is not valid JSON")
        return PromptExecutionSummary(
            eventId = envelope.eventId,
            promptKey = requiredText(node, "promptKey"),
            semVer = readSemVer(node),
            latency = LatencyMs(requiredLong(node, "latencyMs")),
            usage =
                Usage(
                    inputTokens = TokenCount(requiredInt(node, "inputTokens")),
                    outputTokens = TokenCount(requiredInt(node, "outputTokens")),
                ),
            costPerToken = Cost(requiredDecimal(node, "costPerToken")),
            status = readStatus(node),
            retryCount = requiredInt(node, "retryCount"),
            callerSystem = envelope.actor,
            traceId = envelope.traceId,
            occurredAt = envelope.occurredAt,
            variantId = optionalUuid(node, "variantId"),
        )
    }

    /**
     * `variantId`（ADR-0034決定4）はP10b時点の既存イベントに存在しないフィールドであり、
     * 古いイベントを再処理する場合にも壊れず`null`を返す必要がある（他の必須フィールドとは
     * 異なり[requiredText]相当にしない）。
     */
    private fun optionalUuid(
        node: JsonNode,
        field: String,
    ): UUID? =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: throw MalformedPromptExecutedPayloadException("field '$field' is not a valid UUID: '$it'")
        }

    private fun readSemVer(node: JsonNode): SemVer {
        val semVerNode =
            node.get("semVer")?.takeIf { it.isObject }
                ?: throw MalformedPromptExecutedPayloadException("missing or non-object field 'semVer'")
        return SemVer(
            requiredInt(semVerNode, "major"),
            requiredInt(semVerNode, "minor"),
            requiredInt(semVerNode, "patch"),
        )
    }

    private fun readStatus(node: JsonNode): ExecutionStatus {
        val raw = requiredText(node, "status")
        return ExecutionStatus.entries.firstOrNull { it.name == raw }
            ?: throw MalformedPromptExecutedPayloadException("unknown execution status '$raw'")
    }

    private fun requiredText(
        node: JsonNode,
        field: String,
    ): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()
            ?: throw MalformedPromptExecutedPayloadException("missing or non-textual field '$field'")

    private fun requiredInt(
        node: JsonNode,
        field: String,
    ): Int =
        node.get(field)?.takeIf { it.isNumber }?.asInt()
            ?: throw MalformedPromptExecutedPayloadException("missing or non-numeric field '$field'")

    private fun requiredLong(
        node: JsonNode,
        field: String,
    ): Long =
        node.get(field)?.takeIf { it.isNumber }?.asLong()
            ?: throw MalformedPromptExecutedPayloadException("missing or non-numeric field '$field'")

    private fun requiredDecimal(
        node: JsonNode,
        field: String,
    ): BigDecimal =
        node.get(field)?.takeIf { it.isNumber }?.decimalValue()
            ?: throw MalformedPromptExecutedPayloadException("missing or non-numeric field '$field'")
}

/** `PromptExecuted`の`payload`を[PromptExecutionSummary]として解釈できなかった。 */
class MalformedPromptExecutedPayloadException(message: String) : IllegalArgumentException(message)
