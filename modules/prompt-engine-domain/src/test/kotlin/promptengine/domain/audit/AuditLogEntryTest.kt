package promptengine.domain.audit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuditLogEntryTest {
    @Test
    fun `全フィールドを保持する`() {
        val auditId = UUID.randomUUID()
        val occurredAt = Instant.EPOCH

        val entry =
            AuditLogEntry(
                auditId = auditId,
                aggregateType = "Prompt",
                aggregateId = "support/faq",
                action = "Published",
                actor = "user:test",
                payload = "{}",
                traceId = "trace-1",
                occurredAt = occurredAt,
            )

        entry.auditId shouldBe auditId
        entry.aggregateType shouldBe "Prompt"
        entry.aggregateId shouldBe "support/faq"
        entry.action shouldBe "Published"
        entry.actor shouldBe "user:test"
        entry.payload shouldBe "{}"
        entry.traceId shouldBe "trace-1"
        entry.occurredAt shouldBe occurredAt
    }

    @Test
    fun `eventIdは既定でnull（CRUD経路はキーにできるイベントを持たない）`() {
        val entry =
            AuditLogEntry(
                auditId = UUID.randomUUID(),
                aggregateType = "Prompt",
                aggregateId = "support/faq",
                action = "Published",
                actor = "user:test",
                payload = "{}",
                traceId = "trace-1",
                occurredAt = Instant.EPOCH,
            )

        entry.eventId shouldBe null
    }

    @Test
    fun `イベント起点の追記ではeventIdを冪等キーとして保持する`() {
        val eventId = UUID.randomUUID()

        val entry =
            AuditLogEntry(
                auditId = UUID.randomUUID(),
                aggregateType = "Prompt",
                aggregateId = "support/faq",
                action = "PromptPublished",
                actor = "user:test",
                payload = "{}",
                traceId = "trace-1",
                occurredAt = Instant.EPOCH,
                eventId = eventId,
            )

        entry.eventId shouldBe eventId
    }
}
