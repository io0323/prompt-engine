package promptengine.infrastructure.audit

import com.fasterxml.jackson.databind.ObjectMapper
import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditRecord
import promptengine.domain.dlq.DeadLetterEntry
import promptengine.domain.dlq.DeadLetterQueueRepository
import java.time.Clock
import java.time.Instant

/**
 * [AuditFailureHandler]の実DLQ実装（Issue #37クローズ、ADR-0026決定2）。
 *
 * 設計書§2.6ステージ12「失敗時はDLQ退避（本流は止めない）」を、`dead_letter_queue`
 * テーブルへの退避として実装する。P10b以前はログ出力のみの[Slf4jAuditFailureHandler]が
 * 暫定実装だった。
 *
 * ## 退避内容
 * [AuditRecord]はイベントではないため`event_id`を持たない（`null`で退避する）。
 * `event_type`には`"PipelineAudit"`という固定の識別子を入れ、Broker由来の退避
 * （購読側の`eventType`が入る）と区別できるようにする。
 *
 * payloadには[AuditRecord]の構造的フィールドのみをJSON化して入れる。生のprompt/response内容は
 * [AuditRecord]自体が構造的に保持しない（`AuditRecord`のKDoc参照）ため、Secret混入経路は無い。
 * [cause]は`javaClass.simpleName`のみを`failure_reason`へ入れ、例外メッセージ本文も
 * `Throwable`オブジェクト自体も退避内容・ログ経路へ渡さない（[Slf4jAuditFailureHandler]が
 * 確立した方針: インフラ層由来の例外メッセージに接続情報等の秘密が混ざりうるため）。
 *
 * ## 検知
 * 退避が起きたことの検知は[DeadLetterQueueRepository]実装側が担う（1件ごとの構造化ERRORログと
 * [DeadLetterQueueRepository.pendingCount]）。本クラスは追加のログを出さない
 * （同一事象が二重にログへ出るのを避けるため）。
 *
 * [delegate]を与えた場合はDLQ退避に加えて従来の構造化ログも出す。既定は`null`
 * （DLQ実装側のログで足りるため）。
 */
class DeadLetterQueueAuditFailureHandler(
    private val deadLetterQueueRepository: DeadLetterQueueRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
    private val delegate: AuditFailureHandler? = null,
) : AuditFailureHandler {
    override fun handle(
        record: AuditRecord,
        cause: Throwable,
    ) {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "traceId" to record.traceId,
                    "promptKey" to record.promptKey,
                    "mode" to record.mode.name,
                    "stageDurationsMs" to record.stageDurationsMs,
                    "outcome" to outcomeLabel(record.outcome),
                    "occurredAt" to record.occurredAt.toString(),
                ),
            )
        deadLetterQueueRepository.enqueue(
            DeadLetterEntry(
                eventId = null,
                eventType = PIPELINE_AUDIT_EVENT_TYPE,
                subscriberName = SUBSCRIBER_NAME,
                payload = payload,
                failureReason = cause.javaClass.simpleName,
                failedAt = Instant.now(clock),
            ),
        )
        delegate?.let { runCatching { it.handle(record, cause) } }
    }

    private fun outcomeLabel(outcome: AuditOutcome): String =
        when (outcome) {
            is AuditOutcome.Success -> "Success"
            is AuditOutcome.Failure -> "Failure(errorCode=${outcome.errorCode})"
        }

    companion object {
        /** Broker由来の退避（購読側のeventTypeが入る）と区別するための固定識別子。 */
        const val PIPELINE_AUDIT_EVENT_TYPE = "PipelineAudit"

        /** `dead_letter_queue.subscriber_name`。Pipeline Stage 12の退避経路であることを示す。 */
        const val SUBSCRIBER_NAME = "pipeline-audit-stage"
    }
}
