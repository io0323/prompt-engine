package promptengine.tests.integration.messaging

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
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
import promptengine.domain.event.DomainEvent
import promptengine.infrastructure.messaging.OutboxEventBusAdapter
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [OutboxEventBusAdapter]（`EventBusAdapter`の`production`実装、ADR-0025決定6）の
 * Testcontainers(PostgreSQL 16)統合テスト。`publish()`が`event_bus_outbox`へ
 * 封筒の全フィールドを正しくINSERTすることを確認する（実際のBroker中継は
 * [OutboxRelayIntegrationTest]が担当）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxEventBusAdapterIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var adapter: OutboxEventBusAdapter
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate

    private data class FixtureEvent(
        override val eventId: UUID,
        override val occurredAt: Instant,
        override val aggregateId: String,
        override val actor: String,
        override val traceId: String,
        override val payload: Any,
    ) : DomainEvent {
        override val eventType: String = "PromptExecuted"
        override val aggregateType: String = "Prompt"
    }

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
        adapter = OutboxEventBusAdapter(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun `publishしたイベントはevent_bus_outboxへ封筒の全フィールドが書き込まれ未配信状態で始まる`() {
        val eventId = UUID.randomUUID()
        val occurredAt = Instant.parse("2026-01-01T00:00:00Z")
        val event =
            FixtureEvent(
                eventId = eventId,
                occurredAt = occurredAt,
                aggregateId = "prompt/support/faq-answer",
                actor = "system",
                traceId = "trace-xyz",
                payload = mapOf("promptKey" to "support/faq-answer", "inputTokens" to 10),
            )

        adapter.publish(event)

        val row =
            jdbcTemplate.queryForMap(
                "SELECT * FROM event_bus_outbox WHERE event_id = :eventId",
                MapSqlParameterSource("eventId", eventId),
            )
        row["event_type"] shouldBe "PromptExecuted"
        row["aggregate_type"] shouldBe "Prompt"
        row["aggregate_id"] shouldBe "prompt/support/faq-answer"
        row["actor"] shouldBe "system"
        row["trace_id"] shouldBe "trace-xyz"
        row["dispatched_at"] shouldBe null
        row["claimed_at"] shouldBe null
        row["attempts"] shouldBe 0
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
