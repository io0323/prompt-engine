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
import promptengine.domain.event.EventContext
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.Variant
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcExperimentRepository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcExperimentRepository]のTestcontainers(PostgreSQL 16)統合テスト（設計書§12
 * `experiments`/`variants`、ADR-0034）。
 *
 * `variants.version_id`は`prompt_versions`をFK参照するため、各テストは
 * [EventStorePromptRepository]で先にPromptを`Approved`まで進めてからExperimentを保存する。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcExperimentRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var experimentRepository: JdbcExperimentRepository

    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

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
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
        experimentRepository = JdbcExperimentRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    /** Prompt側をApprovedまで進め、Variantが参照できるVersionを用意する。 */
    private fun createApprovedPrompt(): PromptKey {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val semVer = SemVer(1, 0, 0)
        val (created, event) = Prompt.create(key, NewPromptVersion(semVer, PromptContent("body")), context)
        promptRepository.save(created, listOf(event))
        var prompt = promptRepository.findByKey(key)!!.submitForReview(semVer, validationPassed = true)
        promptRepository.save(prompt)
        prompt = promptRepository.findByKey(key)!!.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
        promptRepository.save(prompt)
        return key
    }

    private fun variant(
        name: String,
        weightPct: Int,
    ) = Variant(UUID.randomUUID(), name, SemVer(1, 0, 0), weightPct)

    @Test
    fun `saveしたExperimentはfindByIdで内容が一致するまま復元できる`() {
        val promptKey = createApprovedPrompt()
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 60), variant("treatment", 40)),
                TrafficPolicy(stickyKeyPath = "user.id"),
            )

        experimentRepository.save(experiment)
        val reloaded = experimentRepository.findById(experiment.experimentId)!!

        reloaded.experimentId shouldBe experiment.experimentId
        reloaded.promptKey shouldBe promptKey
        reloaded.type shouldBe ExperimentType.AB
        reloaded.trafficPolicy shouldBe TrafficPolicy(stickyKeyPath = "user.id")
        reloaded.variants.map { it.name to it.weightPct }.toSet() shouldBe setOf("control" to 60, "treatment" to 40)
    }

    @Test
    fun `findActiveByPromptはRunning中のExperimentのみ返す`() {
        val promptKey = createApprovedPrompt()
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 50), variant("treatment", 50)),
                TrafficPolicy(),
            )
        experimentRepository.save(experiment)

        experimentRepository.findActiveByPrompt(promptKey) shouldBe emptyList()

        val (started, event) = experiment.start(context)
        experimentRepository.save(started, listOf(event))

        val active = experimentRepository.findActiveByPrompt(promptKey)
        active.map { it.experimentId } shouldBe listOf(experiment.experimentId)
    }

    @Test
    fun `updateTraffic後の重みが永続化される`() {
        val promptKey = createApprovedPrompt()
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.CANARY,
                listOf(variant("control", 90), variant("treatment", 10)),
                TrafficPolicy(),
            )
        experimentRepository.save(experiment)
        val (started, startedEvent) = experiment.start(context)
        experimentRepository.save(started, listOf(startedEvent))

        val newVariants = started.variants.map { it.copy(weightPct = 50) }
        val updated = started.updateTraffic(newVariants)
        experimentRepository.save(updated)

        val reloaded = experimentRepository.findById(experiment.experimentId)!!
        reloaded.variants.map { it.weightPct }.toSet() shouldBe setOf(50)
    }

    @Test
    fun `イベントはaggregateType Experiment としてdomain_eventsへ追記される`() {
        val promptKey = createApprovedPrompt()
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 50), variant("treatment", 50)),
                TrafficPolicy(),
            )
        experimentRepository.save(experiment)
        val (started, startedEvent) = experiment.start(context)
        experimentRepository.save(started, listOf(startedEvent))
        val (stopped, stoppedEvent) = started.stop(context)
        experimentRepository.save(stopped, listOf(stoppedEvent))

        val rows =
            jdbcTemplate.query(
                """
                SELECT sequence, event_type, aggregate_type
                FROM domain_events WHERE aggregate_id = :experimentId ORDER BY sequence
                """.trimIndent(),
                MapSqlParameterSource("experimentId", experiment.experimentId),
            ) { rs, _ -> Triple(rs.getLong("sequence"), rs.getString("event_type"), rs.getString("aggregate_type")) }

        rows shouldBe
            listOf(
                Triple(1L, "ExperimentStarted", "Experiment"),
                Triple(2L, "ExperimentStopped", "Experiment"),
            )
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
