package promptengine.tests.integration.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcPromptMetadataRepository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcPromptMetadataRepository]のTestcontainers(PostgreSQL 16)統合テスト（ADR-0020）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPromptMetadataRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var metadataRepository: JdbcPromptMetadataRepository
    private lateinit var promptRepository: EventStorePromptRepository

    private val eventContext = EventContext(actor = "user:test", traceId = "fixture-trace", occurredAt = Instant.EPOCH)

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

        val jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
        val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
        metadataRepository = JdbcPromptMetadataRepository(jdbcTemplate)
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun wrap(key: String): String = "---\npe: \"1\"\nkind: prompt\nkey: $key\n---\nhello"

    private fun createPrompt(key: PromptKey) {
        val (created, createdEvent) =
            Prompt.create(key, NewPromptVersion(SemVer(1, 0, 0), PromptContent(wrap(key.value))), eventContext)
        promptRepository.save(created, listOf(createdEvent))
    }

    @Test
    fun `upsertしたメタデータはfindで category description tagsとともに取得できる`() {
        val key = uniqueKey()
        createPrompt(key)

        metadataRepository.upsert(
            PromptMetadata(
                key = key,
                name = "FAQ回答生成",
                category = "support",
                description = "顧客からのFAQに回答する",
                tags = listOf("faq", "customer"),
            ),
        )

        val found = metadataRepository.find(key)!!
        found.name shouldBe "FAQ回答生成"
        found.category shouldBe "support"
        found.description shouldBe "顧客からのFAQに回答する"
        found.tags shouldBe listOf("customer", "faq")
    }

    @Test
    fun `再upsertはtagsを完全に置き換える`() {
        val key = uniqueKey()
        createPrompt(key)
        metadataRepository.upsert(PromptMetadata(key, "name", tags = listOf("a", "b")))

        metadataRepository.upsert(PromptMetadata(key, "name", tags = listOf("c")))

        metadataRepository.find(key)!!.tags shouldBe listOf("c")
    }

    @Test
    fun `同名categoryは同一category_idを再利用する`() {
        val key1 = uniqueKey()
        val key2 = uniqueKey()
        createPrompt(key1)
        createPrompt(key2)

        metadataRepository.upsert(PromptMetadata(key1, "name1", category = "shared"))
        metadataRepository.upsert(PromptMetadata(key2, "name2", category = "shared"))

        metadataRepository.find(key1)!!.category shouldBe "shared"
        metadataRepository.find(key2)!!.category shouldBe "shared"
    }

    @Test
    fun `存在しないkeyはnullを返す`() {
        metadataRepository.find(uniqueKey()) shouldBe null
    }

    private fun uniqueKey(): PromptKey = PromptKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
