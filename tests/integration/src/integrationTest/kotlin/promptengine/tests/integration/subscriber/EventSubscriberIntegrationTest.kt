package promptengine.tests.integration.subscriber

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.redpanda.RedpandaContainer
import promptengine.domain.event.EventContext
import promptengine.domain.event.EventSubscriber
import promptengine.domain.event.EventTopic
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import promptengine.infrastructure.messaging.EventEnvelopeCodec
import promptengine.infrastructure.messaging.OutboxEnvelope
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcAuditRepository
import promptengine.infrastructure.persistence.JdbcDeadLetterQueueRepository
import promptengine.infrastructure.persistence.JdbcExecutionLogRepository
import promptengine.infrastructure.subscriber.AuditEngine
import promptengine.infrastructure.subscriber.ExecutionLogSubscriber
import promptengine.infrastructure.subscriber.KafkaSubscriberRunner
import promptengine.infrastructure.subscriber.PromptExecutedPayloadCodec
import promptengine.infrastructure.subscriber.SubscriberDeadLetterRecorder
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import javax.sql.DataSource

private const val SEND_TIMEOUT_SECONDS = 30L
private const val POLL_DEADLINE_SECONDS = 40L
private const val REAL_SECRET = "sk-live-REAL-SECRET-VALUE"

/**
 * P10bの実購読側（`AuditEngine` / `ExecutionLogSubscriber`）を、実Kafka互換Broker
 * （Testcontainers Redpanda）と実PostgreSQLに対して駆動する統合テスト（ADR-0026）。
 *
 * ADR-0025決定8が方針として定め、`EventBusOutboxIdempotentConsumerIntegrationTest`が
 * テスト専用fixtureで実証した「`event_id` UNIQUE + `ON CONFLICT DO NOTHING`」の冪等性が、
 * **本番の購読側実装**でも実際に効くことをここで確認する（`docs/prompts/p10b.md`テスト要件
 * 「同一イベントの再配信で二重にならないこと」）。
 *
 * あわせて以下も検証する:
 * - Auditの`payload`にSecretの実値が出ないこと（設計書§14の6トピック経路別）
 * - Audit書き込み失敗がDLQへ退避され、本流（ポーリングループ）が止まらないこと（Issue #37）
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventSubscriberIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var kafkaProducer: KafkaProducer<String, String>
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var deadLetterQueueRepository: JdbcDeadLetterQueueRepository

    private val objectMapper = jacksonObjectMapper()
    private val envelopeCodec = EventEnvelopeCodec(objectMapper)
    private val sanitizer = SecretMaskingJsonSanitizer(objectMapper)

    @BeforeAll
    fun setUp() {
        val hikariConfig =
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = postgres.driverClassName
            }
        dataSource = HikariDataSource(hikariConfig)
        Flyway.configure().dataSource(dataSource).load().migrate()
        jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
        val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, objectMapper)
        deadLetterQueueRepository = JdbcDeadLetterQueueRepository(jdbcTemplate)

        // AuditEngineは6トピック全てを購読する。Topicが未作成のまま subscribe すると、
        // 作成後にConsumerが気づくまで metadata.max.age.ms（既定5分）待つことになり
        // テストが不安定になるため、あらかじめ全Topicを作っておく。
        createTopics()

        kafkaProducer =
            KafkaProducer(
                Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.bootstrapServers)
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                    put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                    put(ProducerConfig.ACKS_CONFIG, "all")
                },
            )
    }

    @AfterAll
    fun tearDown() {
        kafkaProducer.close()
        (dataSource as HikariDataSource).close()
    }

    private fun createTopics() {
        val admin =
            Admin.create(
                Properties().apply {
                    put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.bootstrapServers)
                },
            )
        admin.use {
            it.createTopics(EventTopic.entries.map { topic -> NewTopic(topic.topicName, 1, 1.toShort()) })
                .all()
                .get(SEND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun consumerFor(subscriber: EventSubscriber): Consumer<String, String> =
        KafkaConsumer(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.bootstrapServers)
                // group IDをテストごとに一意にして、他テストが進めたオフセットの影響を受けないようにする。
                put(ConsumerConfig.GROUP_ID_CONFIG, "${subscriber.name}-${UUID.randomUUID()}")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            },
        )

    private fun runnerFor(subscriber: EventSubscriber): KafkaSubscriberRunner =
        KafkaSubscriberRunner(
            subscriber = subscriber,
            consumer = consumerFor(subscriber),
            envelopeCodec = envelopeCodec,
            deadLetterRecorder = SubscriberDeadLetterRecorder(deadLetterQueueRepository, envelopeCodec, sanitizer),
            pollTimeout = Duration.ofSeconds(1),
        )

    /**
     * 対象の`eventId`が購読側へ何回渡されたかを数えるデコレータ。
     *
     * Redpandaコンテナはクラス内の全テストで共有され、Topicの中身をテスト間で消せない。
     * さらに`AuditEngine`は6トピック全てを`auto.offset.reset=earliest`で購読するため、
     * 他テストが送ったメッセージも読む。「処理件数が2に達したか」で待つと他テスト由来の
     * メッセージで条件が満たされてしまうため、**対象イベントの配信回数**を直接数える。
     */
    private class DeliveryCountingSubscriber(
        private val delegate: EventSubscriber,
        private val targetEventId: UUID,
    ) : EventSubscriber by delegate {
        @Volatile
        var deliveries: Int = 0
            private set

        override fun handle(envelope: promptengine.domain.event.EventEnvelope) {
            if (envelope.eventId == targetEventId) deliveries++
            delegate.handle(envelope)
        }
    }

    /** [condition]が真になるまで（または[deadline]まで）ポーリングを繰り返す。 */
    private fun pollUntil(
        runner: KafkaSubscriberRunner,
        deadline: Duration = Duration.ofSeconds(POLL_DEADLINE_SECONDS),
        condition: () -> Boolean,
    ): Boolean {
        val until = System.currentTimeMillis() + deadline.toMillis()
        while (!condition() && System.currentTimeMillis() < until) {
            runner.pollOnce()
        }
        return condition()
    }

    private fun send(
        topic: EventTopic,
        envelope: OutboxEnvelope,
    ) {
        kafkaProducer
            .send(ProducerRecord(topic.topicName, envelope.aggregateId, envelopeCodec.encode(envelope)))
            .get(SEND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun createPrompt(): PromptKey {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val content = PromptContent("---\npe: \"1\"\nkind: prompt\nkey: ${key.value}\n---\nhello")
        val eventContext = EventContext(actor = "user:test", traceId = "fixture-trace", occurredAt = Instant.EPOCH)
        val (prompt, event) = Prompt.create(key, NewPromptVersion(SemVer(1, 0, 0), content), eventContext)
        promptRepository.save(prompt, listOf(event))
        return key
    }

    private fun envelope(
        eventId: UUID,
        eventType: String,
        aggregateId: String,
        payload: String,
    ) = OutboxEnvelope(
        outboxId = UUID.randomUUID(),
        eventId = eventId,
        eventType = eventType,
        aggregateType = "Prompt",
        aggregateId = aggregateId,
        actor = "system",
        traceId = "trace-${UUID.randomUUID()}",
        payload = payload,
        occurredAt = Instant.parse("2026-08-09T12:00:00Z"),
        attempts = 0,
    )

    private fun promptExecutedPayload(
        promptKey: String,
        secret: String? = null,
    ): String {
        val secretField = secret?.let { ""","apiSecret":"$it"""" } ?: ""
        return """
            {"promptKey":"$promptKey","semVer":{"major":1,"minor":0,"patch":0},
             "inputTokens":800,"outputTokens":200,"retryCount":0,
             "latencyMs":250,"costPerToken":0.0004,"status":"SUCCESS"$secretField}
            """.trimIndent()
    }

    private fun countWhereEventId(
        table: String,
        eventId: UUID,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM $table WHERE event_id = :eventId",
            MapSqlParameterSource("eventId", eventId),
            Long::class.java,
        ) ?: 0L

    // ---- 冪等性（ADR-0025決定8、docs/prompts/p10b.md テスト要件） ----

    @Test
    fun `同一eventIdのPromptExecutedが2回配信されてもexecution_logsは1行しか残らない`() {
        val key = createPrompt()
        val eventId = UUID.randomUUID()
        val source = envelope(eventId, "PromptExecuted", key.value, promptExecutedPayload(key.value))

        // at-least-once配信を模して同一イベントを2回送る（Broker側は重複排除しない）。
        send(EventTopic.PE_EXECUTION, source)
        send(EventTopic.PE_EXECUTION, source)

        val subscriber =
            DeliveryCountingSubscriber(
                ExecutionLogSubscriber(
                    JdbcExecutionLogRepository(jdbcTemplate),
                    PromptExecutedPayloadCodec(objectMapper),
                ),
                eventId,
            )
        val runner = runnerFor(subscriber)
        try {
            pollUntil(runner) { subscriber.deliveries >= 2 } shouldBe true
        } finally {
            runner.close()
        }

        withClue("購読側には2回届いている（Brokerは重複排除しない）") {
            subscriber.deliveries shouldBe 2
        }
        withClue("ON CONFLICT (event_id) DO NOTHING により2回目の書き込みは捨てられる") {
            countWhereEventId("execution_logs", eventId) shouldBe 1L
        }
    }

    @Test
    fun `同一eventIdのイベントが2回配信されてもaudit_logsは1行しか残らない`() {
        val key = createPrompt()
        val eventId = UUID.randomUUID()
        val source = envelope(eventId, "PromptPublished", key.value, """{"promptKey":"${key.value}"}""")

        send(EventTopic.PE_PROMPT, source)
        send(EventTopic.PE_PROMPT, source)

        val subscriber =
            DeliveryCountingSubscriber(AuditEngine(JdbcAuditRepository(jdbcTemplate, objectMapper), sanitizer), eventId)
        val runner = runnerFor(subscriber)
        try {
            pollUntil(runner) { subscriber.deliveries >= 2 } shouldBe true
        } finally {
            runner.close()
        }

        subscriber.deliveries shouldBe 2
        countWhereEventId("audit_logs", eventId) shouldBe 1L
    }

    // ---- Secretマスク（docs/prompts/p10b.md「Auditのpayloadに Secret の実値が出ないこと（経路別）」） ----

    @Test
    fun `設計書14の6トピックいずれの経路でもaudit_logsのpayloadにSecretの実値が出ない`() {
        val key = createPrompt()
        val routes =
            listOf(
                EventTopic.PE_PROMPT to "PromptPublished",
                EventTopic.PE_EXECUTION to "PromptExecuted",
                EventTopic.PE_EVALUATION to "PromptEvaluationCompleted",
                EventTopic.PE_EXPERIMENT to "ExperimentStarted",
                EventTopic.PE_GOVERNANCE to "PromptApproved",
                EventTopic.PE_PLUGIN to "PluginRegistered",
            )
        val eventIdByRoute =
            routes.associate { (topic, eventType) ->
                val eventId = UUID.randomUUID()
                val payload =
                    """{"promptKey":"${key.value}","apiSecret":"$REAL_SECRET",
                       "nested":{"password":"$REAL_SECRET"},"list":[{"token":"$REAL_SECRET"}]}"""
                send(topic, envelope(eventId, eventType, key.value, payload))
                eventType to eventId
            }

        val runner = runnerFor(AuditEngine(JdbcAuditRepository(jdbcTemplate, objectMapper), sanitizer))
        try {
            pollUntil(runner) {
                eventIdByRoute.values.all { countWhereEventId("audit_logs", it) == 1L }
            } shouldBe true
        } finally {
            runner.close()
        }

        eventIdByRoute.forEach { (eventType, eventId) ->
            val stored =
                jdbcTemplate.queryForObject(
                    "SELECT payload::text FROM audit_logs WHERE event_id = :eventId",
                    MapSqlParameterSource("eventId", eventId),
                    String::class.java,
                )
            withClue("eventType=$eventType") {
                stored.shouldNotBeNullAndNotContainSecret()
            }
        }
    }

    private fun String?.shouldNotBeNullAndNotContainSecret() {
        checkNotNull(this) { "audit_logs row was not written" }
        this shouldNotContain REAL_SECRET
        this shouldContain "***"
    }

    // ---- 実DLQ（Issue #37、docs/prompts/p10b.md テスト要件） ----

    @Test
    fun `Audit書き込みが失敗したイベントはDLQへ退避され後続のイベントは処理され続ける`() {
        val key = createPrompt()
        val pendingBefore = deadLetterQueueRepository.pendingCount()

        // 存在しないpromptKeyを aggregateId に持つイベント。JdbcAuditRepository.record は
        // aggregate_id をサロゲートUUIDへ解決できず失敗する（作為的なモックではない実際の失敗経路）。
        val failingEventId = UUID.randomUUID()
        val unknownKey = "integration-test/never-created-${UUID.randomUUID()}"
        send(EventTopic.PE_PROMPT, envelope(failingEventId, "PromptPublished", unknownKey, """{"a":1}"""))

        // 後続の正常なイベント。1件目の失敗でループが止まらないことの確認に使う。
        val okEventId = UUID.randomUUID()
        send(EventTopic.PE_PROMPT, envelope(okEventId, "PromptPublished", key.value, """{"a":2}"""))

        val runner = runnerFor(AuditEngine(JdbcAuditRepository(jdbcTemplate, objectMapper), sanitizer))
        try {
            pollUntil(runner) {
                countWhereEventId("audit_logs", okEventId) == 1L &&
                    countWhereEventId("dead_letter_queue", failingEventId) == 1L
            } shouldBe true
        } finally {
            runner.close()
        }

        withClue("本流（後続イベント）は失敗せず処理されている") {
            countWhereEventId("audit_logs", okEventId) shouldBe 1L
        }
        withClue("失敗したイベントはaudit_logsへ書かれていない") {
            countWhereEventId("audit_logs", failingEventId) shouldBe 0L
        }

        val dlqRow =
            jdbcTemplate.queryForObject(
                """
                SELECT subscriber_name || '|' || event_type || '|' || status
                FROM dead_letter_queue WHERE event_id = :eventId
                """.trimIndent(),
                MapSqlParameterSource("eventId", failingEventId),
                String::class.java,
            )
        dlqRow shouldBe "${AuditEngine.SUBSCRIBER_NAME}|PromptPublished|PENDING"

        withClue("退避が発生したことがpendingCountで検知できる（ADR-0026決定2）") {
            (deadLetterQueueRepository.pendingCount() > pendingBefore) shouldBe true
        }
    }

    @Test
    fun `同一イベントが繰り返し失敗してもDLQ行は増えずretry_countが加算される`() {
        val unknownKey = "integration-test/never-created-${UUID.randomUUID()}"
        val eventId = UUID.randomUUID()
        val source = envelope(eventId, "PromptPublished", unknownKey, """{"a":1}""")

        send(EventTopic.PE_PROMPT, source)
        send(EventTopic.PE_PROMPT, source)

        val runner = runnerFor(AuditEngine(JdbcAuditRepository(jdbcTemplate, objectMapper), sanitizer))
        try {
            pollUntil(runner) { retryCountOf(eventId) >= 1 } shouldBe true
        } finally {
            runner.close()
        }

        withClue("(event_id, subscriber_name) UNIQUE により行は1件のまま") {
            countWhereEventId("dead_letter_queue", eventId) shouldBe 1L
        }
        retryCountOf(eventId) shouldBe 1
    }

    private fun retryCountOf(eventId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(retry_count), -1) FROM dead_letter_queue WHERE event_id = :eventId",
            MapSqlParameterSource("eventId", eventId),
            Int::class.java,
        ) ?: -1

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        @Container
        @JvmStatic
        val redpanda: RedpandaContainer = RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7")
    }
}
