package promptengine.infrastructure.audit

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditRecord
import promptengine.domain.dlq.DeadLetterEntry
import promptengine.domain.dlq.DeadLetterQueueRepository
import promptengine.domain.pipeline.PipelineMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pipeline Stage 12（Audit）の書き込み失敗を実DLQへ退避する（Issue #37クローズ、ADR-0026決定2）。
 * 設計書§2.6ステージ12「失敗時はDLQ退避（本流は止めない）」。
 */
class DeadLetterQueueAuditFailureHandlerTest {
    private val fixedInstant: Instant = Instant.parse("2026-08-09T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private class RecordingDlq : DeadLetterQueueRepository {
        val enqueued = mutableListOf<DeadLetterEntry>()

        override fun enqueue(entry: DeadLetterEntry) {
            enqueued += entry
        }

        override fun pendingCount(): Long = enqueued.size.toLong()
    }

    private fun record(outcome: AuditOutcome = AuditOutcome.Success) =
        AuditRecord(
            traceId = "trace-1",
            promptKey = "support/faq",
            mode = PipelineMode.FULL_EXECUTION,
            stageDurationsMs = mapOf("Execution" to 250L),
            outcome = outcome,
            occurredAt = Instant.parse("2026-08-09T09:59:00Z"),
        )

    private fun handler(
        dlq: DeadLetterQueueRepository,
        delegate: AuditFailureHandler? = null,
    ) = DeadLetterQueueAuditFailureHandler(dlq, jacksonObjectMapper(), clock, delegate)

    @Test
    fun `AuditRecordをDLQへ退避する`() {
        val dlq = RecordingDlq()

        handler(dlq).handle(record(), IllegalStateException("db down"))

        val entry = dlq.enqueued.single()
        entry.eventType shouldBe DeadLetterQueueAuditFailureHandler.PIPELINE_AUDIT_EVENT_TYPE
        entry.subscriberName shouldBe DeadLetterQueueAuditFailureHandler.SUBSCRIBER_NAME
        entry.failedAt shouldBe fixedInstant
    }

    /** [AuditRecord]はイベントではないため`event_id`を持たない（DLQ側はNULL許容）。 */
    @Test
    fun `eventIdはnullで退避する`() {
        val dlq = RecordingDlq()

        handler(dlq).handle(record(), IllegalStateException("db down"))

        dlq.enqueued.single().eventId shouldBe null
    }

    @Test
    fun `payloadにAuditRecordの構造的フィールドを載せる`() {
        val dlq = RecordingDlq()

        handler(dlq).handle(record(AuditOutcome.Failure("EXECUTION_FAILED")), IllegalStateException("db down"))

        val payload = dlq.enqueued.single().payload
        payload shouldContain """"traceId":"trace-1""""
        payload shouldContain """"promptKey":"support/faq""""
        payload shouldContain """"mode":"FULL_EXECUTION""""
        payload shouldContain """Failure(errorCode=EXECUTION_FAILED)"""
    }

    /**
     * インフラ層由来の例外メッセージには接続情報等が混ざりうるため、例外クラス名のみを残す
     * （`Slf4jAuditFailureHandler`が確立した方針）。
     */
    @Test
    fun `failureReasonは例外クラス名のみで例外メッセージ本文を含まない`() {
        val dlq = RecordingDlq()
        val secretishMessage = "connection refused to jdbc:postgresql://user:PASSWORD@host/db"

        handler(dlq).handle(record(), IllegalStateException(secretishMessage))

        val entry = dlq.enqueued.single()
        entry.failureReason shouldBe "IllegalStateException"
        entry.payload shouldNotContain "PASSWORD"
    }

    @Test
    fun `delegateを与えると構造化ログ側の実装にも委譲する`() {
        val dlq = RecordingDlq()
        var delegated = 0
        val delegate =
            object : AuditFailureHandler {
                override fun handle(
                    record: AuditRecord,
                    cause: Throwable,
                ) {
                    delegated++
                }
            }

        handler(dlq, delegate).handle(record(), IllegalStateException("db down"))

        delegated shouldBe 1
        dlq.enqueued.size shouldBe 1
    }

    @Test
    fun `delegateが例外を投げても退避自体は成立し呼出元へ伝播しない`() {
        val dlq = RecordingDlq()
        val throwingDelegate =
            object : AuditFailureHandler {
                override fun handle(
                    record: AuditRecord,
                    cause: Throwable,
                ): Unit = error("delegate boom")
            }

        handler(dlq, throwingDelegate).handle(record(), IllegalStateException("db down"))

        dlq.enqueued.size shouldBe 1
    }

    @Test
    fun `promptKeyがnullのAuditRecordも退避できる`() {
        val dlq = RecordingDlq()
        val nullKeyRecord =
            AuditRecord(
                traceId = "trace-1",
                promptKey = null,
                mode = PipelineMode.FULL_EXECUTION,
                stageDurationsMs = emptyMap(),
                outcome = AuditOutcome.Success,
                occurredAt = fixedInstant,
            )

        handler(dlq).handle(nullKeyRecord, IllegalStateException("db down"))

        dlq.enqueued.single().payload shouldContain """"promptKey":null"""
    }
}
