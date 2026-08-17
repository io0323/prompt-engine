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
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcGoldenDatasetRepository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcGoldenDatasetRepository]のTestcontainers(PostgreSQL 16)統合テスト（設計書§12
 * `golden_datasets`/`golden_dataset_items`、ADR-0035）。
 *
 * `golden_datasets.prompt_id`は`prompts`をFK参照するのみで、`variants.version_id`と異なり
 * 特定のVersion状態（Approved等）を要求しない。Draft状態のPromptがあれば十分。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcGoldenDatasetRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var datasetRepository: JdbcGoldenDatasetRepository

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
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun createPrompt(): PromptKey {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val (created, event) = Prompt.create(key, NewPromptVersion(SemVer(1, 0, 0), PromptContent("body")), context)
        promptRepository.save(created, listOf(event))
        return key
    }

    private fun item(
        expectedOutput: String? = "expected",
        metadata: Map<String, String> = emptyMap(),
    ) = GoldenDatasetItem(
        itemId = UUID.randomUUID(),
        parameters = mapOf("productName" to "widget", "count" to 3),
        context = mapOf("user" to mapOf("locale" to "ja-JP")),
        expectedOutput = expectedOutput,
        metadata = metadata,
    )

    @Test
    fun `saveしたGoldenDatasetはfindByIdで内容が一致するまま復元できる`() {
        val promptKey = createPrompt()
        val dataset =
            GoldenDataset.create(
                promptKey,
                "smoke-test",
                "説明文",
                listOf(item(), item(expectedOutput = null)),
            )

        datasetRepository.save(dataset)
        val reloaded = datasetRepository.findById(dataset.datasetId)!!

        reloaded.datasetId shouldBe dataset.datasetId
        reloaded.promptKey shouldBe promptKey
        reloaded.name shouldBe "smoke-test"
        reloaded.description shouldBe "説明文"
        reloaded.items shouldBe dataset.items
    }

    @Test
    fun `itemの並び順はposition列で保持され再読込後も維持される`() {
        val promptKey = createPrompt()
        val items = (1..5).map { item() }
        val dataset = GoldenDataset.create(promptKey, "ordering", null, items)

        datasetRepository.save(dataset)
        val reloaded = datasetRepository.findById(dataset.datasetId)!!

        reloaded.items.map { it.itemId } shouldBe items.map { it.itemId }
    }

    @Test
    fun `JSONBの入れ子構造とmetadataも往復できる`() {
        val promptKey = createPrompt()
        val nestedItem =
            item(metadata = mapOf("difficulty" to "hard", "tag" to "edge-case")).copy(
                parameters = mapOf("nested" to mapOf("a" to 1, "b" to listOf("x", "y"))),
                context = mapOf("session" to mapOf("history" to listOf("q1", "q2"))),
            )
        val dataset = GoldenDataset.create(promptKey, "nested-json", null, listOf(nestedItem))

        datasetRepository.save(dataset)
        val reloaded = datasetRepository.findById(dataset.datasetId)!!

        reloaded.items.single().parameters shouldBe nestedItem.parameters
        reloaded.items.single().context shouldBe nestedItem.context
        reloaded.items.single().metadata shouldBe nestedItem.metadata
    }

    @Test
    fun `findByPromptKeyは対象Promptに紐づくデータセットのみ返す`() {
        val promptKeyA = createPrompt()
        val promptKeyB = createPrompt()
        val datasetA = GoldenDataset.create(promptKeyA, "dataset-a", null, listOf(item()))
        val datasetB = GoldenDataset.create(promptKeyB, "dataset-b", null, listOf(item()))
        datasetRepository.save(datasetA)
        datasetRepository.save(datasetB)

        val found = datasetRepository.findByPromptKey(promptKeyA)

        found.map { it.datasetId } shouldBe listOf(datasetA.datasetId)
    }

    @Test
    fun `save後に更新すると古いitemは残らず新しい内容へ入れ替わる`() {
        val promptKey = createPrompt()
        val original = GoldenDataset.create(promptKey, "before-update", null, listOf(item(), item()))
        datasetRepository.save(original)

        val replaced = original.updateItems(listOf(item(expectedOutput = "new-expected")))
        datasetRepository.save(replaced)

        val reloaded = datasetRepository.findById(original.datasetId)!!
        reloaded.items.size shouldBe 1
        reloaded.items.single().expectedOutput shouldBe "new-expected"
    }

    @Test
    fun `findByIdは存在しないIDに対してnullを返す`() {
        datasetRepository.findById(UUID.randomUUID()) shouldBe null
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
