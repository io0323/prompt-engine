package promptengine.domain.dlq

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DeadLetterEntryTest {
    private fun entry(
        eventId: UUID? = UUID.randomUUID(),
        eventType: String = "PromptExecuted",
        subscriberName: String = "audit-engine",
        failureReason: String = "DataAccessException",
    ) = DeadLetterEntry(
        eventId = eventId,
        eventType = eventType,
        subscriberName = subscriberName,
        payload = """{"promptKey":"support/faq"}""",
        failureReason = failureReason,
        failedAt = Instant.EPOCH,
    )

    @Test
    fun `eventTypeが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { entry(eventType = " ") }
    }

    @Test
    fun `subscriberNameが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { entry(subscriberName = "") }
    }

    @Test
    fun `failureReasonが空白の場合は例外を投げる`() {
        shouldThrow<IllegalArgumentException> { entry(failureReason = " ") }
    }

    @Test
    fun `eventIdはnull許容（Pipeline Stage 12のAuditRecord退避経路はイベントを持たない）`() {
        entry(eventId = null).eventId shouldBe null
    }
}
