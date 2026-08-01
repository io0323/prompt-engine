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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcDependencyRepository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcDependencyRepository]のTestcontainers(PostgreSQL 16)統合テスト（ADR-0017）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcDependencyRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var dependencyRepository: JdbcDependencyRepository
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

        jdbcTemplate = NamedParameterJdbcTemplate(dataSource)
        val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
        dependencyRepository = JdbcDependencyRepository(jdbcTemplate)
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun wrap(key: String): String = "---\npe: \"1\"\nkind: prompt\nkey: $key\n---\nhello"

    private fun createPublishedPrompt(key: PromptKey): UUID {
        val (created, createdEvent) =
            Prompt.create(key, NewPromptVersion(SemVer(1, 0, 0), PromptContent(wrap(key.value))), eventContext)
        promptRepository.save(created, listOf(createdEvent))
        var prompt = promptRepository.findByKey(key)!!
        prompt = prompt.submitForReview(SemVer(1, 0, 0), validationPassed = true)
        promptRepository.save(prompt)
        prompt = promptRepository.findByKey(key)!!
        prompt = prompt.approve(SemVer(1, 0, 0), approvalCount = 1, requiredApprovalCount = 1)
        promptRepository.save(prompt)
        prompt = promptRepository.findByKey(key)!!
        val (published, events) = prompt.publish(SemVer(1, 0, 0), allDependenciesPublished = true, eventContext)
        promptRepository.save(published, events)

        return jdbcTemplate.queryForObject(
            """
            SELECT version_id FROM prompt_versions
            WHERE prompt_id = (SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey)
            """.trimIndent(),
            MapSqlParameterSource().addValue("promptKey", key.value),
            UUID::class.java,
        )!!
    }

    private fun insertDependency(
        fromVersionId: UUID,
        toKind: String,
        toKey: String,
        toVersion: String,
    ) {
        jdbcTemplate.update(
            "INSERT INTO dependencies (dependency_id, from_version_id, to_kind, to_key, to_version) " +
                "VALUES (:id, :fromVersionId, :toKind, :toKey, :toVersion)",
            MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("fromVersionId", fromVersionId)
                .addValue("toKind", toKind)
                .addValue("toKey", toKey)
                .addValue("toVersion", toVersion),
        )
    }

    @Test
    fun `findOutboundはPublished版が参照するTemplate Fragmentを返す`() {
        val key = uniqueKey()
        val versionId = createPublishedPrompt(key)
        insertDependency(versionId, "template", "templates/base", "^1")
        insertDependency(versionId, "fragment", "fragments/safety", "^2")

        val edges = dependencyRepository.findOutbound(key)

        edges.map { it.toKind to it.toKey }.toSet() shouldBe
            setOf(DependencyKind.TEMPLATE to "templates/base", DependencyKind.FRAGMENT to "fragments/safety")
        edges.all { it.fromKey == key && it.fromVersion == SemVer(1, 0, 0) } shouldBe true
    }

    @Test
    fun `findOutboundは依存が無ければ空リストを返す`() {
        val key = uniqueKey()
        createPublishedPrompt(key)

        dependencyRepository.findOutbound(key) shouldBe emptyList()
    }

    @Test
    fun `findInboundは自身をPromptとして参照する他Promptの依存を返す`() {
        val referencedKey = uniqueKey()
        createPublishedPrompt(referencedKey)
        val referencingKey = uniqueKey()
        val referencingVersionId = createPublishedPrompt(referencingKey)
        insertDependency(referencingVersionId, "prompt", referencedKey.value, "1.0.0")

        val edges = dependencyRepository.findInbound(referencedKey)

        edges.single().fromKey shouldBe referencingKey
        edges.single().toKey shouldBe referencedKey.value
    }

    private fun uniqueKey(): PromptKey = PromptKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
