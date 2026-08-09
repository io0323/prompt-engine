package promptengine.domain.evaluation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventTopic
import promptengine.domain.event.EventTopicResolver
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PromptEvaluationCompletedEventTest {
    private fun event(sourceEventId: UUID = UUID.randomUUID()) =
        PromptEvaluationCompletedEvent(
            eventId = UUID.randomUUID(),
            occurredAt = Instant.EPOCH,
            aggregateId = "support/faq",
            actor = "system",
            traceId = "trace-1",
            payload =
                PromptEvaluationCompletedEvent.Payload(
                    promptKey = "support/faq",
                    semVer = SemVer(1, 0, 0),
                    sourceEventId = sourceEventId,
                    metrics =
                        listOf(
                            PromptEvaluationCompletedEvent.Metric("Latency", BigDecimal("120"), "measured"),
                        ),
                ),
        )

    @Test
    fun `eventTypeとaggregateTypeは設計書14の名称で固定される`() {
        val created = event()

        created.eventType shouldBe "PromptEvaluationCompleted"
        created.aggregateType shouldBe "Prompt"
    }

    @Test
    fun `eventTypeはpe_evaluationトピックへルーティングされる`() {
        EventTopicResolver.resolve(event().eventType) shouldBe EventTopic.PE_EVALUATION
    }

    @Test
    fun `payloadは算出元のPromptExecutedのeventIdを保持する`() {
        val sourceEventId = UUID.randomUUID()

        event(sourceEventId).payload.sourceEventId shouldBe sourceEventId
    }
}
