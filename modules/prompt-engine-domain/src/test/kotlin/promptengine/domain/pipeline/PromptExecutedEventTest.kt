package promptengine.domain.pipeline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** [PromptExecutedEvent]の契約テスト（設計書§14 `PromptExecuted`）。 */
class PromptExecutedEventTest {
    @Test
    fun `eventType と aggregateType は固定値である`() {
        val event =
            PromptExecutedEvent(
                eventId = UUID.randomUUID(),
                occurredAt = Instant.EPOCH,
                aggregateId = "support/faq",
                actor = "system",
                traceId = "trace-1",
                payload =
                    PromptExecutedEvent.Payload(
                        promptKey = "support/faq",
                        inputTokens = 10,
                        outputTokens = 20,
                        retryCount = 0,
                    ),
            )

        event.eventType shouldBe "PromptExecuted"
        event.aggregateType shouldBe "Prompt"
        (event as DomainEvent).aggregateId shouldBe "support/faq"
    }

    @Test
    fun `payload は構造的な要約フィールドのみを保持する`() {
        val payload =
            PromptExecutedEvent.Payload(
                promptKey = "support/faq",
                inputTokens = 10,
                outputTokens = 20,
                retryCount = 2,
            )

        payload.promptKey shouldBe "support/faq"
        payload.inputTokens shouldBe 10
        payload.outputTokens shouldBe 20
        payload.retryCount shouldBe 2
        payload shouldBe payload.copy()
    }
}
