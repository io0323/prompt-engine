package promptengine.domain.pipeline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.evaluation.ExecutionStatus
import promptengine.domain.event.DomainEvent
import promptengine.domain.event.EventTopic
import promptengine.domain.event.EventTopicResolver
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** [PromptExecutedEvent]の契約テスト（設計書§14 `PromptExecuted`）。 */
class PromptExecutedEventTest {
    private fun payload(retryCount: Int = 0) =
        PromptExecutedEvent.Payload(
            promptKey = "support/faq",
            semVer = SemVer(1, 2, 3),
            inputTokens = 10,
            outputTokens = 20,
            retryCount = retryCount,
            latencyMs = 250,
            costPerToken = BigDecimal("0.0004"),
            status = ExecutionStatus.SUCCESS,
        )

    @Test
    fun `eventType と aggregateType は固定値である`() {
        val event =
            PromptExecutedEvent(
                eventId = UUID.randomUUID(),
                occurredAt = Instant.EPOCH,
                aggregateId = "support/faq",
                actor = "system",
                traceId = "trace-1",
                payload = payload(),
            )

        event.eventType shouldBe "PromptExecuted"
        event.aggregateType shouldBe "Prompt"
        (event as DomainEvent).aggregateId shouldBe "support/faq"
    }

    @Test
    fun `eventTypeはpe_executionトピックへルーティングされる`() {
        EventTopicResolver.resolve("PromptExecuted") shouldBe EventTopic.PE_EXECUTION
    }

    @Test
    fun `payload は構造的な要約フィールドのみを保持する`() {
        val created = payload(retryCount = 2)

        created.promptKey shouldBe "support/faq"
        created.inputTokens shouldBe 10
        created.outputTokens shouldBe 20
        created.retryCount shouldBe 2
        created shouldBe created.copy()
    }

    @Test
    fun `payloadはP10bで追加したsemVer latencyMs costPerToken statusを保持する`() {
        val created = payload()

        created.semVer shouldBe SemVer(1, 2, 3)
        created.latencyMs shouldBe 250L
        created.costPerToken shouldBe BigDecimal("0.0004")
        created.status shouldBe ExecutionStatus.SUCCESS
    }
}
