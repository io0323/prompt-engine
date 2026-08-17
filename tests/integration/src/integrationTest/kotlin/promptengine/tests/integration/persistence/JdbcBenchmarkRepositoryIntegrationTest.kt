package promptengine.tests.integration.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkStatus
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcBenchmarkRepository
import promptengine.infrastructure.persistence.JdbcGoldenDatasetRepository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcBenchmarkRepository]のTestcontainers(PostgreSQL 16)統合テスト（設計書§12
 * `benchmarks`/`benchmark_targets`/`benchmark_metrics`、ADR-0035）。
 *
 * `benchmark_targets.version_id`は`prompt_versions`をFK参照するため、各テストは
 * Promptを`Approved`まで進めてからBenchmarkを保存する（`JdbcExperimentRepositoryIntegrationTest`
 * と同じ理由）。`benchmarks.dataset_id`は`golden_datasets`をFK参照するため、
 * [JdbcGoldenDatasetRepository]で先にGolden Datasetを用意する。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcBenchmarkRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var datasetRepository: JdbcGoldenDatasetRepository
    private lateinit var benchmarkRepository: JdbcBenchmarkRepository

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
        datasetRepository = JdbcGoldenDatasetRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
        benchmarkRepository = JdbcBenchmarkRepository(jdbcTemplate, transactionTemplate)
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    /** Prompt側をApprovedまで進め、Targetが参照できるVersionを用意する。 */
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

    private fun createDataset(promptKey: PromptKey): UUID {
        val item =
            GoldenDatasetItem(
                itemId = UUID.randomUUID(),
                parameters = emptyMap(),
                context = emptyMap(),
                expectedOutput = "expected",
            )
        val dataset = GoldenDataset.create(promptKey, "dataset", null, listOf(item))
        datasetRepository.save(dataset)
        return dataset.datasetId
    }

    private fun target(semVer: SemVer = SemVer(1, 0, 0)) = BenchmarkTarget(UUID.randomUUID(), semVer)

    @Test
    fun `saveしたBenchmarkはfindByIdで内容が一致するまま復元できる`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDataset(promptKey)
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target()),
                setOf(BenchmarkMetricType.Accuracy, BenchmarkMetricType.Consistency),
                nRepetitions = 5,
            )

        benchmarkRepository.save(benchmark)
        val reloaded = benchmarkRepository.findById(benchmark.benchmarkId)!!

        reloaded.benchmarkId shouldBe benchmark.benchmarkId
        reloaded.promptKey shouldBe promptKey
        reloaded.datasetId shouldBe datasetId
        reloaded.status shouldBe BenchmarkStatus.Pending
        reloaded.metrics shouldBe setOf(BenchmarkMetricType.Accuracy, BenchmarkMetricType.Consistency)
        reloaded.nRepetitions shouldBe 5
        reloaded.targets.map { it.promptVersionSemVer } shouldBe listOf(SemVer(1, 0, 0))
    }

    @Test
    fun `startするとstarted_atが設定されRunningとして復元される`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDataset(promptKey)
        val benchmark =
            Benchmark.create(promptKey, datasetId, listOf(target()), setOf(BenchmarkMetricType.Accuracy), 3)
        benchmarkRepository.save(benchmark)

        val started = benchmark.start()
        benchmarkRepository.save(started)

        val reloaded = benchmarkRepository.findById(benchmark.benchmarkId)!!
        reloaded.status shouldBe BenchmarkStatus.Running

        val startedAt =
            jdbcTemplate.queryForObject(
                "SELECT started_at FROM benchmarks WHERE benchmark_id = :id",
                MapSqlParameterSource("id", benchmark.benchmarkId),
                Timestamp::class.java,
            )
        startedAt.shouldNotBeNull()
    }

    @Test
    fun `Pendingのままではstarted_atは設定されない`() {
        val promptKey = createApprovedPrompt()
        val datasetId = createDataset(promptKey)
        val benchmark =
            Benchmark.create(promptKey, datasetId, listOf(target()), setOf(BenchmarkMetricType.Accuracy), 3)

        benchmarkRepository.save(benchmark)

        val startedAt =
            jdbcTemplate.queryForObject(
                "SELECT started_at FROM benchmarks WHERE benchmark_id = :id",
                MapSqlParameterSource("id", benchmark.benchmarkId),
                Timestamp::class.java,
            )
        startedAt.shouldBeNull()
    }

    @Test
    fun `複数Targetを保存し順序を問わず復元できる`() {
        val promptKey = createApprovedPrompt()
        val secondSemVer = SemVer(1, 1, 0)
        val (secondCreated, secondEvent) =
            promptRepository.findByKey(promptKey)!!
                .newVersion(NewPromptVersion(secondSemVer, PromptContent("body v2")), context)
        promptRepository.save(secondCreated, listOf(secondEvent))
        var secondVersionPrompt =
            promptRepository.findByKey(
                promptKey,
            )!!.submitForReview(secondSemVer, validationPassed = true)
        promptRepository.save(secondVersionPrompt)
        secondVersionPrompt =
            promptRepository.findByKey(promptKey)!!.approve(secondSemVer, approvalCount = 1, requiredApprovalCount = 1)
        promptRepository.save(secondVersionPrompt)

        val datasetId = createDataset(promptKey)
        val benchmark =
            Benchmark.create(
                promptKey,
                datasetId,
                listOf(target(SemVer(1, 0, 0)), target(secondSemVer)),
                setOf(BenchmarkMetricType.Accuracy),
                3,
            )
        benchmarkRepository.save(benchmark)

        val reloaded = benchmarkRepository.findById(benchmark.benchmarkId)!!
        reloaded.targets.map { it.promptVersionSemVer }.toSet() shouldBe setOf(SemVer(1, 0, 0), secondSemVer)
    }

    @Test
    fun `findByPromptKeyは対象Promptに紐づくBenchmarkのみ返す`() {
        val promptKeyA = createApprovedPrompt()
        val promptKeyB = createApprovedPrompt()
        val datasetA = createDataset(promptKeyA)
        val datasetB = createDataset(promptKeyB)
        val benchmarkA =
            Benchmark.create(promptKeyA, datasetA, listOf(target()), setOf(BenchmarkMetricType.Accuracy), 3)
        val benchmarkB =
            Benchmark.create(promptKeyB, datasetB, listOf(target()), setOf(BenchmarkMetricType.Accuracy), 3)
        benchmarkRepository.save(benchmarkA)
        benchmarkRepository.save(benchmarkB)

        val found = benchmarkRepository.findByPromptKey(promptKeyA)

        found.map { it.benchmarkId } shouldBe listOf(benchmarkA.benchmarkId)
    }

    @Test
    fun `findByIdは存在しないIDに対してnullを返す`() {
        benchmarkRepository.findById(UUID.randomUUID()) shouldBe null
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
