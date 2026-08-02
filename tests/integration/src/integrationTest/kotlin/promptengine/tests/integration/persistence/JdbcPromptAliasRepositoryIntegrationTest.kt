package promptengine.tests.integration.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
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
import promptengine.domain.prompt.PromptAlias
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcPromptAliasRepository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcPromptAliasRepository]のTestcontainers(PostgreSQL 16)統合テスト（P8バグ修正）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPromptAliasRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var aliasRepository: JdbcPromptAliasRepository
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
        aliasRepository = JdbcPromptAliasRepository(jdbcTemplate)
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun wrap(key: String): String = "---\npe: \"1\"\nkind: prompt\nkey: $key\n---\nhello"

    private fun createPrompt(
        key: PromptKey,
        semVer: SemVer,
    ) {
        val (prompt, event) = Prompt.create(key, NewPromptVersion(semVer, PromptContent(wrap(key.value))), eventContext)
        promptRepository.save(prompt, listOf(event))
    }

    @Test
    fun `upsertしたAliasはfindで解決先Versionを返す`() {
        val key = uniqueKey()
        createPrompt(key, SemVer(1, 0, 0))

        aliasRepository.upsert(PromptAlias(key, "stable", SemVer(1, 0, 0)))
        val found = aliasRepository.find(key, "stable")

        found shouldBe PromptAlias(key, "stable", SemVer(1, 0, 0))
    }

    @Test
    fun `同一aliasへの再upsertは参照先Versionを更新する`() {
        val key = uniqueKey()
        createPrompt(key, SemVer(1, 0, 0))
        val prompt = promptRepository.findByKey(key)!!
        val newVersion = NewPromptVersion(SemVer(2, 0, 0), PromptContent(wrap(key.value)))
        promptRepository.save(prompt.newVersion(newVersion, eventContext).first)

        aliasRepository.upsert(PromptAlias(key, "stable", SemVer(1, 0, 0)))
        aliasRepository.upsert(PromptAlias(key, "stable", SemVer(2, 0, 0)))

        aliasRepository.find(key, "stable") shouldBe PromptAlias(key, "stable", SemVer(2, 0, 0))
    }

    @Test
    fun `存在しないaliasを検索するとnullを返す`() {
        val key = uniqueKey()
        createPrompt(key, SemVer(1, 0, 0))

        aliasRepository.find(key, "missing") shouldBe null
    }

    @Test
    fun `存在しないVersionへのupsertはPromptVersionNotFoundExceptionを投げる`() {
        val key = uniqueKey()
        createPrompt(key, SemVer(1, 0, 0))

        shouldThrow<PromptVersionNotFoundException> {
            aliasRepository.upsert(PromptAlias(key, "stable", SemVer(9, 9, 9)))
        }
    }

    @Test
    fun `findAllはPromptに設定された全Aliasをalias名順で返す`() {
        val key = uniqueKey()
        createPrompt(key, SemVer(1, 0, 0))
        aliasRepository.upsert(PromptAlias(key, "stable", SemVer(1, 0, 0)))
        aliasRepository.upsert(PromptAlias(key, "canary", SemVer(1, 0, 0)))

        val all = aliasRepository.findAll(key)

        all shouldBe listOf(PromptAlias(key, "canary", SemVer(1, 0, 0)), PromptAlias(key, "stable", SemVer(1, 0, 0)))
    }

    private fun uniqueKey(): PromptKey = PromptKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
