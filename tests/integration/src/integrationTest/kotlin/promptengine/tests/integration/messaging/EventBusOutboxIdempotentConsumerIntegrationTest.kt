package promptengine.tests.integration.messaging

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.redpanda.RedpandaContainer
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private const val SEND_TIMEOUT_SECONDS = 10L

/**
 * ADR-0025決定8（購読側冪等性パターン）が実際のBrokerに対して機能することを証明する、
 * テスト専用の最小Consumer fixture（`docs/prompts/p10a.md`「同じイベントが2回配信された
 * ときに購読側が二重処理しないこと」）。
 *
 * このテストは本番のSubscriber実装ではない。10bで追加されるAudit/Evaluation Engineの
 * 実Subscriberが採用すべきパターン（`event_id UNIQUE` + `INSERT ... ON CONFLICT DO NOTHING`）
 * が、実際のKafka互換Broker（Redpanda）から消費した場合にも重複を排除できることのみを
 * 検証する。使い捨てのテスト用テーブル（`test_only_idempotent_sink`）はFlywayマイグレーション
 * ではなく本テストが直接DDLを発行して作る。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventBusOutboxIdempotentConsumerIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var kafkaProducer: KafkaProducer<String, String>

    private val topic = "pe.execution"

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
        jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
        jdbcTemplate.update(
            """
            CREATE TABLE IF NOT EXISTS test_only_idempotent_sink (
                event_id UUID PRIMARY KEY,
                payload VARCHAR NOT NULL
            )
            """.trimIndent(),
            MapSqlParameterSource(),
        )

        val producerProps =
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.ACKS_CONFIG, "all")
            }
        kafkaProducer = KafkaProducer(producerProps)
    }

    @AfterAll
    fun tearDown() {
        kafkaProducer.close()
        (dataSource as HikariDataSource).close()
    }

    private fun sendAndWait(
        topic: String,
        eventId: UUID,
        payload: String,
    ) {
        kafkaProducer.send(
            ProducerRecord(topic, eventId.toString(), payload),
        ).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** [event_id] UNIQUE制約への`ON CONFLICT DO NOTHING`（ADR-0025決定8の購読側パターン）。 */
    private fun applyIdempotently(
        eventId: UUID,
        payload: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO test_only_idempotent_sink (event_id, payload)
            VALUES (:eventId, :payload)
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
            MapSqlParameterSource().addValue("eventId", eventId).addValue("payload", payload),
        )
    }

    @Test
    fun `同じevent_idのメッセージが2回配信されても購読側の書き込みは1行しか残らない`() {
        val eventId = UUID.randomUUID()
        val payload = """{"eventId":"$eventId"}"""

        // at-least-once配信を模して、同一event_idのメッセージを意図的に2回Brokerへ送る
        // （Redpanda自体は重複排除しない。ADR-0025決定8はこの重複を購読側で吸収する方針）。
        sendAndWait(topic, eventId, payload)
        sendAndWait(topic, eventId, payload)

        val consumerProps =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.bootstrapServers)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.GROUP_ID_CONFIG, "idempotent-test-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            }
        val consumer = KafkaConsumer<String, String>(consumerProps)
        consumer.subscribe(listOf(topic))

        try {
            val consumedPayloads = mutableListOf<String>()
            val deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis()
            while (consumedPayloads.size < 2 && System.currentTimeMillis() < deadline) {
                val records = consumer.poll(Duration.ofSeconds(2))
                records.forEach { record -> consumedPayloads += record.value() }
            }
            consumedPayloads.size shouldBe 2

            consumedPayloads.forEach { applyIdempotently(eventId, it) }

            val rowCount =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM test_only_idempotent_sink WHERE event_id = :eventId",
                    MapSqlParameterSource("eventId", eventId),
                    Long::class.java,
                )
            rowCount shouldBe 1L
        } finally {
            consumer.close()
        }
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        @Container
        @JvmStatic
        val redpanda: RedpandaContainer = RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v24.2.7")
    }
}
