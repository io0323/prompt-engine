package promptengine.infrastructure.messaging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test
    fun `publishと並行してsnapshotを呼んでもConcurrentModificationExceptionを起こさない`() {
        // Collections.synchronizedListのtoList()はモニタで保護されないイテレーションを行うため、
        // 他スレッドの並行書き込みと衝突しうる（CodeRabbitレビュー指摘）。synchronized(published)で
        // イテレーション全体を囲むことで、多数の並行書き込み下でもsnapshotが例外を起こさないことを
        // ストレス的に検証する。
        val adapter = InMemoryEventBusAdapter(setOf("local"))
        val writerCount = 8
        val writesPerWriter = 500
        val executor = Executors.newFixedThreadPool(writerCount + 1)
        val readyLatch = CountDownLatch(writerCount + 1)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(writerCount + 1)
        val failures = mutableListOf<Throwable>()

        repeat(writerCount) { writerIndex ->
            executor.submit {
                readyLatch.countDown()
                startLatch.await()
                try {
                    repeat(writesPerWriter) { i -> adapter.publish(FakeEvent("writer-$writerIndex-$i")) }
                } catch (t: Throwable) {
                    synchronized(failures) { failures += t }
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        executor.submit {
            readyLatch.countDown()
            startLatch.await()
            try {
                repeat(writesPerWriter) { adapter.snapshot() }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

        readyLatch.await()
        startLatch.countDown()
        doneLatch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        failures shouldBe emptyList()
    }
}
