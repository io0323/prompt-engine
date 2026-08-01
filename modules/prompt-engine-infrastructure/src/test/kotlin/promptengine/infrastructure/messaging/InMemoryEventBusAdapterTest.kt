package promptengine.infrastructure.messaging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** [InMemoryEventBusAdapter]の単体テスト（ADR-0015決定7）。 */
class InMemoryEventBusAdapterTest {
    private data class FakeEvent(override val traceId: String) : DomainEvent {
        override val eventId: UUID = UUID.randomUUID()
        override val eventType: String = "Fake"
        override val occurredAt: Instant = Instant.EPOCH
        override val aggregateType: String = "Fake"
        override val aggregateId: String = "fake-1"
        override val actor: String = "system"
        override val payload: Any = Unit
    }

    @Test
    fun `production プロファイルでは構築時にIllegalStateExceptionを投げる`() {
        shouldThrow<IllegalStateException> { InMemoryEventBusAdapter(setOf("production")) }
    }

    @Test
    fun `production 以外のプロファイルでは構築でき publishしたイベントをsnapshotで取得できる`() {
        val adapter = InMemoryEventBusAdapter(setOf("local"))

        adapter.publish(FakeEvent("trace-1"))
        adapter.publish(FakeEvent("trace-2"))

        adapter.snapshot().map { it.traceId } shouldBe listOf("trace-1", "trace-2")
    }
}
