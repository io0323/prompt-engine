package promptengine.infrastructure.subscriber

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventEnvelope
import promptengine.domain.event.EventSubscriber
import promptengine.domain.event.EventTopic
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import promptengine.infrastructure.messaging.EventEnvelopeCodec
import promptengine.infrastructure.messaging.OutboxEnvelope
import java.time.Instant
import java.util.UUID

/**
 * 購読側の駆動（ポーリング・DLQ退避・オフセットコミット、ADR-0026決定1・決定2）。
 * 実Brokerは使わず`kafka-clients`の[MockConsumer]で駆動部分だけを検証する
 * （実Brokerに対する検証は`tests/integration`のRedpandaテストが担う）。
 */
class KafkaSubscriberRunnerTest {
    private val codec = EventEnvelopeCodec(testObjectMapper)
    private val sanitizer = SecretMaskingJsonSanitizer(testObjectMapper)
    private val topic = EventTopic.PE_EXECUTION.topicName
    private val partition = TopicPartition(topic, 0)

    private companion object {
        const val REAL_SECRET = "sk-live-REAL-SECRET-VALUE"
    }

    private class CapturingSubscriber(
        private val failWith: Throwable? = null,
    ) : EventSubscriber {
        val handled = mutableListOf<EventEnvelope>()

        override val name: String = "test-subscriber"
        override val topics: Set<EventTopic> = setOf(EventTopic.PE_EXECUTION)

        override fun handle(envelope: EventEnvelope) {
            failWith?.let { throw it }
            handled += envelope
        }
    }

    private fun mockConsumer(): MockConsumer<String, String> =
        MockConsumer<String, String>("earliest").apply {
            schedulePollTask {
                rebalance(listOf(partition))
                updateBeginningOffsets(mapOf(partition to 0L))
            }
        }

    private fun encodedEnvelope(payload: String = promptExecutedPayload()): String =
        codec.encode(
            OutboxEnvelope(
                outboxId = UUID.randomUUID(),
                eventId = UUID.randomUUID(),
                eventType = "PromptExecuted",
                aggregateType = "Prompt",
                aggregateId = "support/faq",
                actor = "system",
                traceId = "trace-1",
                payload = payload,
                occurredAt = Instant.parse("2026-08-09T00:00:00Z"),
                attempts = 0,
            ),
        )

    private fun runner(
        subscriber: EventSubscriber,
        consumer: MockConsumer<String, String>,
        dlq: RecordingDeadLetterQueueRepository,
    ) = KafkaSubscriberRunner(
        subscriber = subscriber,
        consumer = consumer,
        envelopeCodec = codec,
        deadLetterRecorder = SubscriberDeadLetterRecorder(dlq, codec, sanitizer),
    )

    private fun MockConsumer<String, String>.addRecord(
        offset: Long,
        value: String,
    ) {
        addRecord(ConsumerRecord(topic, 0, offset, "support/faq", value))
    }

    @Test
    fun `届いたメッセージを封筒へ復元して購読側へ渡す`() {
        val subscriber = CapturingSubscriber()
        val consumer = mockConsumer()
        val dlq = RecordingDeadLetterQueueRepository()
        val runnerUnderTest = runner(subscriber, consumer, dlq)
        runnerUnderTest.pollOnce()
        consumer.addRecord(0, encodedEnvelope())

        val handled = runnerUnderTest.pollOnce()

        handled shouldBe 1
        subscriber.handled.single().eventType shouldBe "PromptExecuted"
        dlq.enqueued shouldBe emptyList()
    }

    @Test
    fun `メッセージが無ければ0件を返す`() {
        val consumer = mockConsumer()
        val runnerUnderTest = runner(CapturingSubscriber(), consumer, RecordingDeadLetterQueueRepository())

        runnerUnderTest.pollOnce() shouldBe 0
    }

    @Test
    fun `購読側が例外を投げたらDLQへ退避し本流は継続する`() {
        val subscriber = CapturingSubscriber(failWith = IllegalStateException("db down"))
        val consumer = mockConsumer()
        val dlq = RecordingDeadLetterQueueRepository()
        val runnerUnderTest = runner(subscriber, consumer, dlq)
        runnerUnderTest.pollOnce()
        consumer.addRecord(0, encodedEnvelope())

        // 例外がpollOnceの外へ伝播しないこと（＝1件の失敗でループが止まらない）。
        val handled = runnerUnderTest.pollOnce()

        handled shouldBe 0
        val entry = dlq.enqueued.single()
        entry.subscriberName shouldBe "test-subscriber"
        entry.eventType shouldBe "PromptExecuted"
        entry.failureReason shouldBe "IllegalStateException"
    }

    @Test
    fun `失敗しても後続のメッセージは処理される（poison pillでループを止めない）`() {
        var shouldFail = true
        val subscriber =
            object : EventSubscriber {
                val handled = mutableListOf<EventEnvelope>()

                override val name: String = "test-subscriber"
                override val topics: Set<EventTopic> = setOf(EventTopic.PE_EXECUTION)

                override fun handle(envelope: EventEnvelope) {
                    if (shouldFail) {
                        shouldFail = false
                        error("first one fails")
                    }
                    handled += envelope
                }
            }
        val consumer = mockConsumer()
        val dlq = RecordingDeadLetterQueueRepository()
        val runnerUnderTest = runner(subscriber, consumer, dlq)
        runnerUnderTest.pollOnce()
        consumer.addRecord(0, encodedEnvelope())
        consumer.addRecord(1, encodedEnvelope())

        val handled = runnerUnderTest.pollOnce()

        handled shouldBe 1
        subscriber.handled.size shouldBe 1
        dlq.enqueued.size shouldBe 1
    }

    @Test
    fun `封筒としてデコードできない本文はUndecodableEnvelopeとして退避する`() {
        val consumer = mockConsumer()
        val dlq = RecordingDeadLetterQueueRepository()
        val runnerUnderTest = runner(CapturingSubscriber(), consumer, dlq)
        runnerUnderTest.pollOnce()
        consumer.addRecord(0, "<<<not json>>>")

        runnerUnderTest.pollOnce() shouldBe 0

        val entry = dlq.enqueued.single()
        entry.eventId shouldBe null
        entry.eventType shouldBe KafkaSubscriberRunner.UNDECODABLE_EVENT_TYPE
        entry.failureReason shouldBe "MalformedEventEnvelopeException"
    }

    @Test
    fun `DLQへ退避するpayloadもSecretマスクを通る`() {
        val subscriber = CapturingSubscriber(failWith = IllegalStateException("db down"))
        val consumer = mockConsumer()
        val dlq = RecordingDeadLetterQueueRepository()
        val runnerUnderTest = runner(subscriber, consumer, dlq)
        runnerUnderTest.pollOnce()
        consumer.addRecord(0, encodedEnvelope(payload = """{"apiSecret":"$REAL_SECRET"}"""))

        runnerUnderTest.pollOnce()

        dlq.enqueued.single().payload shouldNotContain REAL_SECRET
    }

    @Test
    fun `処理後にオフセットをコミットする（退避済みを再消費し続けない）`() {
        val subscriber = CapturingSubscriber()
        val consumer = mockConsumer()
        val runnerUnderTest = runner(subscriber, consumer, RecordingDeadLetterQueueRepository())
        runnerUnderTest.pollOnce()
        consumer.addRecord(0, encodedEnvelope())

        runnerUnderTest.pollOnce()

        consumer.committed(setOf(partition))[partition]?.offset() shouldBe 1L
    }

    @Test
    fun `購読は初回のpollOnceで一度だけ行われる`() {
        val consumer = mockConsumer()
        val runnerUnderTest = runner(CapturingSubscriber(), consumer, RecordingDeadLetterQueueRepository())

        runnerUnderTest.pollOnce()
        runnerUnderTest.pollOnce()

        consumer.subscription() shouldBe setOf(topic)
    }

    @Test
    fun `closeでConsumerを閉じる`() {
        val consumer = mockConsumer()

        runner(CapturingSubscriber(), consumer, RecordingDeadLetterQueueRepository()).close()

        consumer.closed() shouldBe true
    }
}
