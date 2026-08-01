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
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptSearchCriteria
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcPromptMetadataRepository
import promptengine.infrastructure.persistence.JdbcPromptSearchRepository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcPromptSearchRepository]のTestcontainers(PostgreSQL 16)統合テスト（ADR-0017）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcPromptSearchRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var searchRepository: JdbcPromptSearchRepository
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
        searchRepository = JdbcPromptSearchRepository(jdbcTemplate)
        metadataRepository = JdbcPromptMetadataRepository(jdbcTemplate, transactionTemplate)
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    private fun wrap(key: String): String = "---\npe: \"1\"\nkind: prompt\nkey: $key\n---\nhello"

    private fun createDraftPrompt(
        key: PromptKey,
        name: String,
        category: String? = null,
        tags: List<String> = emptyList(),
    ) {
        val (created, createdEvent) =
            Prompt.create(key, NewPromptVersion(SemVer(1, 0, 0), PromptContent(wrap(key.value))), eventContext)
        promptRepository.save(created, listOf(createdEvent))
        metadataRepository.upsert(PromptMetadata(key, name, category = category, tags = tags))
    }

    private fun publish(key: PromptKey) {
        var prompt = promptRepository.findByKey(key)!!
        prompt = prompt.submitForReview(SemVer(1, 0, 0), validationPassed = true)
        promptRepository.save(prompt)
        prompt = promptRepository.findByKey(key)!!
        prompt = prompt.approve(SemVer(1, 0, 0), approvalCount = 1, requiredApprovalCount = 1)
        promptRepository.save(prompt)
        prompt = promptRepository.findByKey(key)!!
        val (published, events) = prompt.publish(SemVer(1, 0, 0), allDependenciesPublished = true, eventContext)
        promptRepository.save(published, events)
    }

    @Test
    fun `qはnameとprompt_keyの部分一致で検索する`() {
        val key = uniqueKey()
        createDraftPrompt(key, "FAQ回答生成システム")

        val page = searchRepository.search(PromptSearchCriteria(q = "FAQ回答"))

        page.items.map { it.key } shouldBe listOf(key)
    }

    @Test
    fun `categoryとtagで絞り込める`() {
        val matching = uniqueKey()
        val wrongCategory = uniqueKey()
        val wrongTag = uniqueKey()
        createDraftPrompt(matching, "match", category = "support", tags = listOf("faq"))
        createDraftPrompt(wrongCategory, "wrong-category", category = "sales", tags = listOf("faq"))
        createDraftPrompt(wrongTag, "wrong-tag", category = "support", tags = listOf("other"))

        val page = searchRepository.search(PromptSearchCriteria(category = "support", tag = "faq"))

        page.items.map { it.key } shouldBe listOf(matching)
    }

    @Test
    fun `statusはDraftとPublishedを区別する`() {
        // 同一クラス内の他テストと同じPostgresコンテナを共有する（TestInstance.Lifecycle.PER_CLASS）
        // ため、statusのみでの絞り込みは他テストが作ったDraft状態のPromptも拾ってしまう。
        // qも合わせて指定し、このテスト自身が作ったPromptだけを対象にする。
        val prefix = "status-check-${UUID.randomUUID()}"
        val draftKey = uniqueKey()
        val publishedKey = uniqueKey()
        createDraftPrompt(draftKey, "$prefix-draft")
        createDraftPrompt(publishedKey, "$prefix-published")
        publish(publishedKey)

        val draftResult = searchRepository.search(PromptSearchCriteria(q = prefix, status = LifecycleState.Draft))
        draftResult.items.map { it.key } shouldBe listOf(draftKey)
        val publishedResult =
            searchRepository.search(PromptSearchCriteria(q = prefix, status = LifecycleState.Published))
        publishedResult.items.map { it.key } shouldBe listOf(publishedKey)
        publishedResult.items.single().publishedVersion shouldBe "1.0.0"
    }

    @Test
    fun `pageとsizeでページングする`() {
        val prefix = "paging-${UUID.randomUUID()}"
        repeat(3) { i -> createDraftPrompt(PromptKey("integration-test/$prefix-$i"), "$prefix-$i") }

        val page0 = searchRepository.search(PromptSearchCriteria(q = prefix, page = 0, size = 2))
        val page1 = searchRepository.search(PromptSearchCriteria(q = prefix, page = 1, size = 2))

        page0.items.size shouldBe 2
        page1.items.size shouldBe 1
        page0.totalElements shouldBe 3L
    }

    private fun uniqueKey(): PromptKey = PromptKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
