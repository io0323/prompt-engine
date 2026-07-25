package promptengine.domain.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 設計書 §14 の共通封筒フィールド
 * {eventId, eventType, occurredAt, aggregateType, aggregateId, actor, traceId, payload}
 * を持つ基底型の契約テスト。個々のPromptイベントでの実際の値の検証は PromptTest 側で行う。
 */
class DomainEventTest {
    private data class FixtureEvent(
        override val eventId: UUID,
        override val eventType: String,
        override val occurredAt: Instant,
        override val aggregateType: String,
        override val aggregateId: String,
        override val actor: String,
        override val traceId: String,
        override val payload: String,
    ) : DomainEvent

    @Test
    fun `DomainEvent は共通封筒フィールドを持つ`() {
        val eventId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

        val event: DomainEvent =
            FixtureEvent(
                eventId = eventId,
                eventType = "FixtureEvent",
                occurredAt = occurredAt,
                aggregateType = "Fixture",
                aggregateId = "support/faq-answer",
                actor = "user:alice",
                traceId = "trace-1",
                payload = "payload",
            )

        event.eventId shouldBe eventId
        event.eventType shouldBe "FixtureEvent"
        event.occurredAt shouldBe occurredAt
        event.aggregateType shouldBe "Fixture"
        event.aggregateId shouldBe "support/faq-answer"
        event.actor shouldBe "user:alice"
        event.traceId shouldBe "trace-1"
        event.payload shouldBe "payload"
    }
}
