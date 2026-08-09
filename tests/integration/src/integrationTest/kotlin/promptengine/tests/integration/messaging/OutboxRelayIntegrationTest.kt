package promptengine.tests.integration.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import promptengine.infrastructure.messaging.DomainEventOutboxSource
import promptengine.infrastructure.messaging.EventBusOutboxSource
import promptengine.infrastructure.messaging.EventEnvelopeCodec
import promptengine.infrastructure.messaging.EventProducer
import promptengine.infrastructure.messaging.KafkaEventProducer
import promptengine.infrastructure.messaging.OutboxRelayer
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private const val RETRY_INTERVAL_MS = 500L

/**
 * Outbox → Broker中継（[OutboxRelayer]、`EventBusOutboxSource`/`DomainEventOutboxSource`）の
 * Testcontainers(PostgreSQL 16 + Redpanda)統合テスト（ADR-0025、`docs/prompts/p10a.md`
 * テスト要件）。
 *
 * - `event_bus_outbox`/既存`outbox`いずれに書かれたイベントもBrokerへ中継されること
 * - クレームタイムアウトによる再クレーム（プロセスクラッシュ後もイベントが失われないこと）
 * - 複数インスタンス下で同じ行を二重にクレームしない（`SKIP LOCKED`）こと
 *
 * Postgres統合テストと同様、Docker起動を前提としスキップ機構は持たない
 * （`docs/prompts/p10a.md`「0件ガードと同じ問題になります」）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxRelayIntegrationTest {
    private val envelopeCodec = EventEnvelopeCodec(jacksonObjectMapper())

    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var producer: KafkaEventProducer
    private lateinit var kafkaProducer: KafkaProducer<String, String>

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
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))

        val producerProps =
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.ACKS_CONFIG, "all")
            }
        kafkaProducer = KafkaProducer(producerProps)
        producer = KafkaEventProducer(kafkaProducer)

        // Topicの自動作成・リーダー選出待ちで初回送信がタイムアウトに近づく事象
        // （relayUntilDispatchedのKDoc参照）を減らすため、このテストで使う全Topicを
        // 事前に作成しておく（CodeRabbitレビュー指摘）。retryUntilDispatchedの
        // リトライループ自体は、事前作成後も残る自動作成外の一過性遅延に備えて維持する。
        Admin.create(
            Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.bootstrapServers) },
        ).use { admin ->
            admin.createTopics(listOf("pe.execution", "pe.prompt").map { NewTopic(it, 1, 1.toShort()) })
                .all()
                .get(30, TimeUnit.SECONDS)
        }
    }

    @AfterAll
    fun tearDown() {
        kafkaProducer.close()
        (dataSource as HikariDataSource).close()
    }

    /**
     * Postgresコンテナ・テーブルはクラス内の全テストで共有する（`@TestInstance(PER_CLASS)`、
     * `JdbcAuditRepositoryIntegrationTest`と同じ理由でコンテナ起動コストを1回に抑える）ため、
     * DB側の状態は各テスト開始前に空にし、`relayOnce()`の戻り値（件数）を決定的にする。
     * Kafka Topic自体はテスト間で消せない（Redpandaコンテナも同様にクラス内で共有）ため、
     * Kafka側の検証は件数一致ではなく「期待するpayloadが含まれるか」で行う
     * （[pollUntilContains]）。
     */
    @BeforeEach
    fun cleanOutboxTables() {
        jdbcTemplate.update("TRUNCATE TABLE event_bus_outbox, outbox, domain_events", MapSqlParameterSource())
    }

    /** [fragment]を含むレコードの本文そのものを返す（見つからなければnull）。 */
    private fun pollForBodyContaining(
        kafkaConsumer: KafkaConsumer<String, String>,
        fragment: String,
        timeout: Duration = Duration.ofSeconds(15),
    ): String? {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            val records = kafkaConsumer.poll(Duration.ofSeconds(2))
            records.firstOrNull { it.value().contains(fragment) }?.let { return it.value() }
        }
        return null
    }

    /**
     * 本文に[expectedFragment]を含むレコードを受信するまで、[timeout]を上限にポーリングし続ける。
     *
     * P10b（ADR-0025訂正E1・ADR-0026決定1a）でBrokerメッセージ本文がpayload単体から
     * 設計書§14の封筒全体のJSONへ変わったため、完全一致ではなく部分一致で判定する
     * （本テストの関心は「対象イベントがBrokerへ届いたか」であり、封筒8フィールドが
     * 揃っていることは`Brokerへ中継されるメッセージ本文は設計書14の封筒8フィールドを
     * すべて含む`が検証する）。
     */
    private fun pollUntilContains(
        kafkaConsumer: KafkaConsumer<String, String>,
        expectedFragment: String,
        timeout: Duration = Duration.ofSeconds(15),
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (System.currentTimeMillis() < deadline) {
            val records = kafkaConsumer.poll(Duration.ofSeconds(2))
            if (records.any { it.value().contains(expectedFragment) }) return true
        }
        return false
    }

    /**
     * Redpandaへの初回送信はTopicの自動作成・リーダー選出待ちで
     * [KafkaEventProducer]の送信タイムアウトへ到達することがある（本番の`@Scheduled`
     * ポーリングでは次回サイクルの再試行で自然に解消する一過性の事象、ADR-0025決定4の
     * バックオフがまさにこれに対応する）。テストでも同じ「次のポーリングで再試行される」
     * 前提を反映し、[relayer].relayOnce()を[timeout]を上限に配信件数が[expectedCount]に
     * 達するまで呼び直す。
     */
    private fun relayUntilDispatched(
        relayer: OutboxRelayer,
        expectedCount: Int = 1,
        timeout: Duration = Duration.ofSeconds(20),
    ): Int {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        var totalDispatched = 0
        while (totalDispatched < expectedCount && System.currentTimeMillis() < deadline) {
            totalDispatched += relayer.relayOnce()
            if (totalDispatched < expectedCount) Thread.sleep(RETRY_INTERVAL_MS)
        }
        return totalDispatched
    }

    private fun consumer(vararg topics: String): KafkaConsumer<String, String> {
        val props =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.bootstrapServers)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.GROUP_ID_CONFIG, "test-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            }
        val kafkaConsumer = KafkaConsumer<String, String>(props)
        kafkaConsumer.subscribe(topics.toList())
        return kafkaConsumer
    }

    private fun insertEventBusOutboxRow(
        eventType: String = "PromptExecuted",
        aggregateId: String = "prompt/example-${UUID.randomUUID()}",
        claimedAt: Instant? = null,
        attempts: Int = 0,
    ): UUID {
        val eventId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO event_bus_outbox
                (outbox_id, event_id, event_type, aggregate_type, aggregate_id, actor, trace_id,
                 payload, occurred_at, created_at, claimed_at, attempts, next_attempt_at)
            VALUES
                (:outboxId, :eventId, :eventType, 'Prompt', :aggregateId, 'system', 'trace-1',
                 :payload::json, :now, :now, :claimedAt, :attempts, :claimableSince)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("eventId", eventId)
                .addValue("eventType", eventType)
                .addValue("aggregateId", aggregateId)
                .addValue("payload", """{"eventId":"$eventId"}""")
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("claimableSince", Timestamp.from(CLAIMABLE_SINCE.invoke()))
                .addValue("claimedAt", claimedAt?.let { Timestamp.from(it) })
                .addValue("attempts", attempts),
        )
        return eventId
    }

    /** `appendDomainEvents`（`DomainEventAppender`）と同じ形の行をdomain_events/outboxへ直接書く。 */
    private fun insertDomainEventOutboxRow(eventType: String = "PromptPublished"): UUID {
        val eventId = UUID.randomUUID()
        val aggregateId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO domain_events
                (event_id, aggregate_type, aggregate_id, sequence, event_type, actor, trace_id, payload, occurred_at)
            VALUES
                (:eventId, 'Prompt', :aggregateId, 1, :eventType, 'system', 'trace-1', :payload::json, :now)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("aggregateId", aggregateId)
                .addValue("eventType", eventType)
                .addValue("payload", """{"eventId":"$eventId"}""")
                .addValue("now", Timestamp.from(Instant.now())),
        )
        jdbcTemplate.update(
            """
            INSERT INTO outbox (outbox_id, event_id, created_at, next_attempt_at)
            VALUES (:outboxId, :eventId, :now, :claimableSince)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("eventId", eventId)
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("claimableSince", Timestamp.from(CLAIMABLE_SINCE.invoke())),
        )
        return eventId
    }

    @Test
    fun `event_bus_outboxに書かれたイベントがEventBusOutboxSource経由でBrokerへ中継される`() {
        val eventId = insertEventBusOutboxRow(eventType = "PromptExecuted")
        val source = EventBusOutboxSource(jdbcTemplate, transactionTemplate)
        val relayer = OutboxRelayer(source, producer, envelopeCodec, instanceId = "instance-1")
        val kafkaConsumer = consumer("pe.execution")

        try {
            val dispatched = relayUntilDispatched(relayer)
            dispatched shouldBe 1

            pollUntilContains(kafkaConsumer, """{"eventId":"$eventId"}""") shouldBe true

            val dispatchedAt =
                jdbcTemplate.query(
                    "SELECT dispatched_at FROM event_bus_outbox WHERE event_id = :eventId",
                    MapSqlParameterSource("eventId", eventId),
                ) { rs, _ -> rs.getTimestamp("dispatched_at") }.single()
            dispatchedAt.shouldNotBeNull()
        } finally {
            kafkaConsumer.close()
        }
    }

    /**
     * 設計書§14「イベント封筒: {eventId, eventType, occurredAt, aggregateType, aggregateId,
     * actor, traceId, payload}」への準拠を、実Brokerへ中継した実メッセージで固定する。
     *
     * P10a時点の実装は本文に`payload`列のJSONだけを載せており、封筒の他の7フィールドが
     * 欠落していた（設計書§14違反。ADR-0025訂正E1）。P10bで封筒全体を送る形へ是正したため、
     * 8フィールドすべてが実際にBrokerを通って受信側へ届くことをここで検証する。
     */
    @Test
    fun `Brokerへ中継されるメッセージ本文は設計書14の封筒8フィールドをすべて含む`() {
        val aggregateId = "prompt/envelope-conformance-${UUID.randomUUID()}"
        val eventId = insertEventBusOutboxRow(eventType = "PromptExecuted", aggregateId = aggregateId)
        val source = EventBusOutboxSource(jdbcTemplate, transactionTemplate)
        val relayer = OutboxRelayer(source, producer, envelopeCodec, instanceId = "instance-1")
        val kafkaConsumer = consumer("pe.execution")

        try {
            relayUntilDispatched(relayer) shouldBe 1

            val body = pollForBodyContaining(kafkaConsumer, eventId.toString())
            body.shouldNotBeNull()

            // 受信側が封筒として復元でき、8フィールドすべてが中継元の値と一致すること。
            val received = envelopeCodec.decode(body)
            received.eventId shouldBe eventId
            received.eventType shouldBe "PromptExecuted"
            received.aggregateType shouldBe "Prompt"
            received.aggregateId shouldBe aggregateId
            received.actor shouldBe "system"
            received.traceId shouldBe "trace-1"
            received.occurredAt.shouldNotBeNull()
            // payloadは文字列エスケープされず入れ子のJSONオブジェクトとして埋め込まれる。
            jacksonObjectMapper().readTree(received.payload).get("eventId").asText() shouldBe eventId.toString()

            // 本文のトップレベルに§14の8キーが揃っていること（復元経路とは独立に生JSONで確認）。
            val fieldNames = jacksonObjectMapper().readTree(body).fieldNames().asSequence().toSet()
            fieldNames shouldBe
                setOf(
                    "eventId",
                    "eventType",
                    "occurredAt",
                    "aggregateType",
                    "aggregateId",
                    "actor",
                    "traceId",
                    "payload",
                )
        } finally {
            kafkaConsumer.close()
        }
    }

    @Test
    fun `既存outboxとdomain_eventsに書かれたPrompt状態遷移イベントがDomainEventOutboxSource経由でBrokerへ中継される`() {
        val eventId = insertDomainEventOutboxRow(eventType = "PromptPublished")
        val source = DomainEventOutboxSource(jdbcTemplate, transactionTemplate)
        val relayer = OutboxRelayer(source, producer, envelopeCodec, instanceId = "instance-1")
        val kafkaConsumer = consumer("pe.prompt")

        try {
            val dispatched = relayUntilDispatched(relayer)
            dispatched shouldBe 1

            pollUntilContains(kafkaConsumer, """{"eventId":"$eventId"}""") shouldBe true
        } finally {
            kafkaConsumer.close()
        }
    }

    @Test
    fun `クレームしたプロセスが落ちてもclaimTimeoutを過ぎれば別のクレームで再取得され配信される`() {
        // claimed_atを過去（クレームしたプロセスがクラッシュしたとみなせる時刻）に手動で
        // 設定してシミュレートする（テスト要件「中継の途中でプロセスが落ちた場合に、
        // イベントが失われないこと」）。
        val eventId =
            insertEventBusOutboxRow(
                eventType = "PromptExecuted",
                claimedAt = Instant.now().minus(Duration.ofHours(1)),
                attempts = 0,
            )
        val source = EventBusOutboxSource(jdbcTemplate, transactionTemplate)
        val relayer =
            OutboxRelayer(
                source,
                producer,
                envelopeCodec,
                instanceId = "instance-2",
                claimTimeout = Duration.ofSeconds(1),
            )
        val kafkaConsumer = consumer("pe.execution")

        try {
            val dispatched = relayUntilDispatched(relayer)
            dispatched shouldBe 1

            pollUntilContains(kafkaConsumer, """{"eventId":"$eventId"}""") shouldBe true
        } finally {
            kafkaConsumer.close()
        }
    }

    @Test
    fun `複数インスタンスが同時にクレームしても同じ行を二重にクレームしない`() {
        val rowCount = 20
        val eventIds = (1..rowCount).map { insertEventBusOutboxRow(eventType = "PromptExecuted") }
        val source = EventBusOutboxSource(jdbcTemplate, transactionTemplate)

        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        val claimedByInstance1 = mutableListOf<UUID>()
        val claimedByInstance2 = mutableListOf<UUID>()

        val task1 =
            executor.submit {
                startLatch.await()
                val claimed = source.claimBatch("instance-a", Duration.ofSeconds(30), rowCount)
                claimedByInstance1 += claimed.map { it.eventId }
            }
        val task2 =
            executor.submit {
                startLatch.await()
                val claimed = source.claimBatch("instance-b", Duration.ofSeconds(30), rowCount)
                claimedByInstance2 += claimed.map { it.eventId }
            }
        startLatch.countDown()
        task1.get(30, TimeUnit.SECONDS)
        task2.get(30, TimeUnit.SECONDS)
        executor.shutdown()

        val overlap = claimedByInstance1.intersect(claimedByInstance2.toSet())
        overlap shouldBe emptySet()
        (claimedByInstance1 + claimedByInstance2).toSet() shouldBe eventIds.toSet()
    }

    @Test
    fun `claim_timeoutを過ぎて別インスタンスに再claimされた行を元インスタンスのmarkDispatchedは上書きしない`() {
        // ADR-0025決定3「フェンシング」。A→claim_timeout経過→B→AがmarkDispatchedを試みる、
        // という順序を実際のPostgresに対して再現する（CodeRabbitレビュー指摘）。
        val eventId = insertEventBusOutboxRow(eventType = "PromptExecuted")
        val source = EventBusOutboxSource(jdbcTemplate, transactionTemplate)
        val shortClaimTimeout = Duration.ofSeconds(1)

        val claimedByA = source.claimBatch("instance-a", shortClaimTimeout, 10)
        claimedByA.map { it.eventId } shouldBe listOf(eventId)

        Thread.sleep(shortClaimTimeout.toMillis() + 2000)

        val claimedByB = source.claimBatch("instance-b", shortClaimTimeout, 10)
        claimedByB.map { it.eventId } shouldBe listOf(eventId)

        // Aはクレームした時点のoutboxIdをまだ持っている（クラッシュしていなかった、または
        // Broker送信の応答が遅れていただけのケースを模す）が、行の所有権はもうBにある。
        val owned = source.markDispatched(claimedByA.single().outboxId, "instance-a")
        owned shouldBe false

        val row =
            jdbcTemplate.queryForMap(
                "SELECT claimed_by, dispatched_at FROM event_bus_outbox WHERE event_id = :eventId",
                MapSqlParameterSource("eventId", eventId),
            )
        row["claimed_by"] shouldBe "instance-b"
        row["dispatched_at"] shouldBe null
    }

    @Test
    fun `claim_timeoutを過ぎて別インスタンスに再claimされた行を元インスタンスのmarkFailedは上書きしない`() {
        // BがmarkDispatched/markFailedのいずれも呼ぶ前（＝Bがまだ行をアクティブに
        // クレームしたままの状態）に、AがmarkFailedを試みるケースを再現する。
        // 先にBが自分のmarkFailedを呼んでしまうと（claimed_by=NULLへ解放されるのが
        // 元々の仕様、ADR-0025決定3フェーズ3）、Bの所有権自体が消えてしまい
        // フェンシングの検証にならないため、Bはクレームしたままにする。
        val eventId = insertEventBusOutboxRow(eventType = "PromptExecuted")
        val source = EventBusOutboxSource(jdbcTemplate, transactionTemplate)
        val shortClaimTimeout = Duration.ofSeconds(1)

        val claimedByA = source.claimBatch("instance-a", shortClaimTimeout, 10)
        Thread.sleep(shortClaimTimeout.toMillis() + 2000)
        val claimedByB = source.claimBatch("instance-b", shortClaimTimeout, 10)
        claimedByB.map { it.eventId } shouldBe listOf(eventId)

        // Aが自分のクレーム時点のoutboxIdでmarkFailedを試みても、所有権は既にBにあるため
        // 反映されない（Bはまだ自分の確定処理を呼んでいない、フェンシングが試される状態）。
        val owned = source.markFailed(claimedByA.single().outboxId, "instance-a", Instant.now().plusSeconds(1))
        owned shouldBe false

        val row =
            jdbcTemplate.queryForMap(
                "SELECT claimed_by, attempts FROM event_bus_outbox WHERE event_id = :eventId",
                MapSqlParameterSource("eventId", eventId),
            )
        row["claimed_by"] shouldBe "instance-b"
        row["attempts"] shouldBe 0
    }

    @Test
    fun `event_bus_outboxのBroker送信失敗はmarkFailedでクレーム解放と再試行時刻の前進が実DBへ反映される`() {
        // OutboxRelayerTest（単体テスト）はOutboxSourceをモックするため、markFailedの
        // 実SQL（claimed_atのNULL化・attemptsの加算・next_attempt_atの前進）自体は
        // 一度も実DBに対して実行されない。ここでは意図的に失敗するEventProducerを渡し、
        // EventBusOutboxSource.markFailedの実装を実際のPostgresに対して検証する。
        val eventId = insertEventBusOutboxRow(eventType = "PromptExecuted")
        val source = EventBusOutboxSource(jdbcTemplate, transactionTemplate)
        val failingProducer =
            EventProducer { _, _, _, _ ->
                throw RuntimeException("simulated broker failure")
            }
        val relayer = OutboxRelayer(source, failingProducer, envelopeCodec, instanceId = "instance-3")

        val dispatched = relayer.relayOnce()

        dispatched shouldBe 0
        val row =
            jdbcTemplate.queryForMap(
                """
                SELECT claimed_at, attempts, dispatched_at, next_attempt_at
                FROM event_bus_outbox WHERE event_id = :eventId
                """.trimIndent(),
                MapSqlParameterSource("eventId", eventId),
            )
        row["claimed_at"] shouldBe null
        row["dispatched_at"] shouldBe null
        row["attempts"] shouldBe 1
        (row["next_attempt_at"] as java.sql.Timestamp).toInstant().isAfter(Instant.now()) shouldBe true
    }

    @Test
    fun `既存outboxのBroker送信失敗もmarkFailedでクレーム解放と再試行時刻の前進が実DBへ反映される`() {
        val eventId = insertDomainEventOutboxRow(eventType = "PromptPublished")
        val source = DomainEventOutboxSource(jdbcTemplate, transactionTemplate)
        val failingProducer =
            EventProducer { _, _, _, _ ->
                throw RuntimeException("simulated broker failure")
            }
        val relayer = OutboxRelayer(source, failingProducer, envelopeCodec, instanceId = "instance-3")

        val dispatched = relayer.relayOnce()

        dispatched shouldBe 0
        val row =
            jdbcTemplate.queryForMap(
                "SELECT claimed_at, attempts, dispatched_at, next_attempt_at FROM outbox WHERE event_id = :eventId",
                MapSqlParameterSource("eventId", eventId),
            )
        row["claimed_at"] shouldBe null
        row["dispatched_at"] shouldBe null
        row["attempts"] shouldBe 1
        (row["next_attempt_at"] as java.sql.Timestamp).toInstant().isAfter(Instant.now()) shouldBe true
    }

    private companion object {
        /**
         * クレーム対象行の`next_attempt_at`に入れる「確実に過去」の時刻。
         *
         * 従来これらのフィクスチャは`next_attempt_at`を指定せずDDLの`DEFAULT now()`
         * （**DBサーバのクロック**）に任せていた。一方`OutboxSource.claimBatch`の
         * `next_attempt_at <= :now`の`:now`は**JVMのクロック**である。両者がわずかでも
         * ずれている（Docker上のPostgresとホストJVMでは実際に起こりうる）と、挿入直後の行が
         * 「まだ再試行時刻に達していない」と判定されてクレームされず、`attempts`が加算されない。
         *
         * 実際にP10bで統合テストと単体テストを同一実行に含めた際、負荷増加とともに
         * `既存outboxのBroker送信失敗も...`が`attempts: expected 1 but was 0`で
         * 断続的に落ちた（クレーム0件が原因）。テスト側で明示的に過去時刻を入れ、
         * クロック一致への依存を断つ。
         */
        val CLAIMABLE_SINCE: () -> Instant = { Instant.now().minusSeconds(60) }

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        @Container
        @JvmStatic
        val redpanda: RedpandaContainer = RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7")
    }
}
