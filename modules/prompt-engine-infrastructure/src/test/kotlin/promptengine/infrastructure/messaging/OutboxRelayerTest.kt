package promptengine.infrastructure.messaging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** [OutboxRelayer]の単体テスト（ADR-0025決定2〜4）。[OutboxSource]/[EventProducer]をモックする。 */
class OutboxRelayerTest {
    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    private val envelopeCodec = EventEnvelopeCodec(jacksonObjectMapper())

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(OutboxRelayer::class.java) as Logger
        logAppender = ListAppender()
        logAppender.start()
        logger.addAppender(logAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(logAppender)
    }

    private fun envelope(
        eventType: String = "PromptExecuted",
        aggregateId: String = "prompt/example",
        attempts: Int = 0,
    ) = OutboxEnvelope(
        outboxId = UUID.randomUUID(),
        eventId = UUID.randomUUID(),
        eventType = eventType,
        aggregateType = "Prompt",
        aggregateId = aggregateId,
        actor = "system",
        traceId = "trace-1",
        payload = """{"k":"v"}""",
        occurredAt = Instant.EPOCH,
        attempts = attempts,
    )

    @Test
    fun `クレームした行の送信に成功したらmarkDispatchedを呼び件数を返す`() {
        val source = mockk<OutboxSource>(relaxed = true)
        val producer = mockk<EventProducer>(relaxed = true)
        val env = envelope()
        every { source.claimBatch(any(), any(), any()) } returns listOf(env)
        every { source.markDispatched(env.outboxId, "worker-1") } returns true

        val relayer = OutboxRelayer(source, producer, envelopeCodec, instanceId = "worker-1")
        val dispatched = relayer.relayOnce()

        dispatched shouldBe 1
        // 本文は payload 単体ではなく封筒全体のJSON（ADR-0026決定1でP10aのワイヤ形式を変更）。
        verify { producer.send("pe.execution", "prompt/example", envelopeCodec.encode(env), env.eventId) }
        verify { source.markDispatched(env.outboxId, "worker-1") }
        verify(exactly = 0) { source.markFailed(any(), any(), any()) }
        logAppender.list shouldBe emptyList()
    }

    @Test
    fun `aggregateIdが空文字の場合はeventIdをキーとして送信する`() {
        val source = mockk<OutboxSource>(relaxed = true)
        val producer = mockk<EventProducer>(relaxed = true)
        val env = envelope(aggregateId = "")
        every { source.claimBatch(any(), any(), any()) } returns listOf(env)

        OutboxRelayer(source, producer, envelopeCodec, instanceId = "worker-1").relayOnce()

        verify { producer.send("pe.execution", env.eventId.toString(), any(), env.eventId) }
    }

    @Test
    fun `送信に失敗した行はmarkFailedを呼びmarkDispatchedは呼ばない`() {
        val source = mockk<OutboxSource>(relaxed = true)
        val producer = mockk<EventProducer>()
        val env = envelope(attempts = 0)
        every { source.claimBatch(any(), any(), any()) } returns listOf(env)
        every { producer.send(any(), any(), any(), any()) } throws RuntimeException("broker unavailable")
        every { source.markFailed(env.outboxId, "worker-1", any()) } returns true

        val relayer = OutboxRelayer(source, producer, envelopeCodec, instanceId = "worker-1")
        val dispatched = relayer.relayOnce()

        dispatched shouldBe 0
        verify(exactly = 0) { source.markDispatched(any(), any()) }
        verify { source.markFailed(env.outboxId, "worker-1", any()) }
        logAppender.list shouldBe emptyList()
    }

    @Test
    fun `クレーム結果が空なら何も送信せず0を返す`() {
        val source = mockk<OutboxSource>()
        val producer = mockk<EventProducer>()
        every { source.claimBatch(any(), any(), any()) } returns emptyList()

        val dispatched = OutboxRelayer(source, producer, envelopeCodec, instanceId = "worker-1").relayOnce()

        dispatched shouldBe 0
        verify(exactly = 0) { producer.send(any(), any(), any(), any()) }
    }

    @Test
    fun `未知のeventTypeはEventTopicResolverの例外がそのまま伝播しmarkDispatchedもmarkFailedも呼ばれない`() {
        // EventTopicResolver.resolveはrunCatchingの外側で呼ばれる（トピック解決不能は
        // 送信失敗として扱わずクレームしたまま次回に持ち越す設計。ADR-0025は未知のeventTypeを
        // 「設計書にないイベント」として明示的にフェイルファストする方針であり、
        // Outbox行を「配信失敗」として静かにリトライループへ入れることは意図しない）。
        val source = mockk<OutboxSource>(relaxed = true)
        val producer = mockk<EventProducer>(relaxed = true)
        val env = envelope(eventType = "NotInDesignDoc")
        every { source.claimBatch(any(), any(), any()) } returns listOf(env)

        try {
            OutboxRelayer(source, producer, envelopeCodec, instanceId = "worker-1").relayOnce()
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e.message?.contains("NotInDesignDoc") shouldBe true
        }
        verify(exactly = 0) { producer.send(any(), any(), any(), any()) }
        verify(exactly = 0) { source.markDispatched(any(), any()) }
        verify(exactly = 0) { source.markFailed(any(), any(), any()) }
    }

    @Test
    fun `複数件クレームした場合は全件について送信を試みる`() {
        val source = mockk<OutboxSource>(relaxed = true)
        val producer = mockk<EventProducer>(relaxed = true)
        val envelopes = listOf(envelope(), envelope(), envelope())
        every { source.claimBatch(any(), any(), any()) } returns envelopes

        val dispatched = OutboxRelayer(source, producer, envelopeCodec, instanceId = "worker-1").relayOnce()

        dispatched shouldBe 3
        verifyOrder {
            producer.send(any(), any(), any(), any())
            producer.send(any(), any(), any(), any())
            producer.send(any(), any(), any(), any())
        }
    }

    @Test
    fun `送信成功後にmarkDispatchedがfalseを返す場合(別インスタンスに再claimされていた)はfencing_lostをログに残し件数はカウントする`() {
        // 送信自体はBrokerへ成功しているため件数には数える。ただしDB側の確定は
        // 別インスタンス（claim_timeout後に再claimしたインスタンス）の所有物になっており、
        // このインスタンスが上書きしてはならない（フェンシング、ADR-0025決定3）。
        val source = mockk<OutboxSource>(relaxed = true)
        val producer = mockk<EventProducer>(relaxed = true)
        val env = envelope()
        every { source.claimBatch(any(), any(), any()) } returns listOf(env)
        every { source.markDispatched(env.outboxId, "worker-1") } returns false

        val dispatched = OutboxRelayer(source, producer, envelopeCodec, instanceId = "worker-1").relayOnce()

        dispatched shouldBe 1
        logAppender.list.shouldNotBeEmpty()
        val logged = logAppender.list.single().formattedMessage
        logged.contains("outbox_relay_fencing_lost") shouldBe true
        logged.contains(env.outboxId.toString()) shouldBe true
        logged.contains("markDispatched") shouldBe true
    }

    @Test
    fun `送信失敗後にmarkFailedがfalseを返す場合(別インスタンスに再claimされていた)はfencing_lostをログに残す`() {
        val source = mockk<OutboxSource>(relaxed = true)
        val producer = mockk<EventProducer>()
        val env = envelope()
        every { source.claimBatch(any(), any(), any()) } returns listOf(env)
        every { producer.send(any(), any(), any(), any()) } throws RuntimeException("broker unavailable")
        every { source.markFailed(env.outboxId, "worker-1", any()) } returns false

        val dispatched = OutboxRelayer(source, producer, envelopeCodec, instanceId = "worker-1").relayOnce()

        dispatched shouldBe 0
        logAppender.list.shouldNotBeEmpty()
        val logged = logAppender.list.single().formattedMessage
        logged.contains("outbox_relay_fencing_lost") shouldBe true
        logged.contains(env.outboxId.toString()) shouldBe true
        logged.contains("markFailed") shouldBe true
    }

    @Test
    fun `backoffForは1回目1秒から倍々に増え上限5分でクリップされる`() {
        OutboxRelayer.backoffFor(1) shouldBe Duration.ofSeconds(1)
        OutboxRelayer.backoffFor(2) shouldBe Duration.ofSeconds(2)
        OutboxRelayer.backoffFor(3) shouldBe Duration.ofSeconds(4)
        OutboxRelayer.backoffFor(10) shouldBe Duration.ofMinutes(5)
        OutboxRelayer.backoffFor(1000) shouldBe Duration.ofMinutes(5)
    }
}
