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
import promptengine.domain.governance.ApprovalPolicy
import promptengine.domain.governance.ReviewCase
import promptengine.domain.governance.ReviewCaseStatus
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcReviewCaseRepository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcReviewCaseRepository] のTestcontainers(PostgreSQL 16)統合テスト（設計書§12、ADR-0032）。
 *
 * `review_cases`は`prompt_versions.version_id`をFKとして参照するため、各テストは
 * [EventStorePromptRepository]で先にPromptを`InReview`まで進めてからReviewCaseを保存する。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcReviewCaseRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var reviewCaseRepository: JdbcReviewCaseRepository

    private val context =
        EventContext(
            actor = "user:author",
            traceId = "trace-1",
            occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

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
        reviewCaseRepository = JdbcReviewCaseRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    /** Prompt側をDraft→InReviewまで進め、ReviewCaseのFK先となるVersionを用意する。 */
    private fun createInReviewPrompt(): Pair<PromptKey, SemVer> {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val semVer = SemVer(0, 1, 0)
        val (created, event) = Prompt.create(key, NewPromptVersion(semVer, PromptContent("body")), context)
        promptRepository.save(created, listOf(event))
        val loaded = promptRepository.findByKey(key)!!
        promptRepository.save(loaded.submitForReview(semVer, validationPassed = true))
        return key to semVer
    }

    @Test
    fun `saveしたReviewCaseはfindInReviewで内容が一致するまま復元できる`() {
        val (promptKey, semVer) = createInReviewPrompt()
        val (reviewCase, event) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 2, allowSelfApproval = false),
                context,
            )
        reviewCaseRepository.save(reviewCase, listOf(event))

        val reloaded = reviewCaseRepository.findInReview(promptKey, semVer)!!

        reloaded.reviewId shouldBe reviewCase.reviewId
        reloaded.promptKey shouldBe promptKey
        reloaded.semVer shouldBe semVer
        reloaded.submittedBy shouldBe context.actor
        reloaded.requiredApprovals shouldBe 2
        reloaded.status shouldBe ReviewCaseStatus.InReview
        reloaded.approvals shouldBe emptyList()
    }

    @Test
    fun `approveを重ねて承認数に達するとApprovedへ遷移しfindInReviewはnullを返す`() {
        val (promptKey, semVer) = createInReviewPrompt()
        val (created, createdEvent) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 2, allowSelfApproval = false),
                context,
            )
        reviewCaseRepository.save(created, listOf(createdEvent))

        val loaded1 = reviewCaseRepository.findInReview(promptKey, semVer)!!
        val (afterFirst, _) =
            loaded1.approve(
                "user:approver1",
                null,
                allowSelfApproval = false,
                context = context.copy(actor = "user:approver1"),
            )
        reviewCaseRepository.save(afterFirst)

        val loaded2 = reviewCaseRepository.findInReview(promptKey, semVer)!!
        loaded2.approvalCount shouldBe 1
        val (afterSecond, approvedEvent) =
            loaded2.approve(
                "user:approver2",
                "looks good",
                allowSelfApproval = false,
                context = context.copy(actor = "user:approver2"),
            )
        reviewCaseRepository.save(afterSecond, listOfNotNull(approvedEvent))

        // Approved化したためInReviewの検索対象から外れる。
        reviewCaseRepository.findInReview(promptKey, semVer) shouldBe null

        val finalStatus =
            jdbcTemplate.queryForObject(
                "SELECT status FROM review_cases WHERE review_id = :reviewId",
                MapSqlParameterSource("reviewId", created.reviewId),
                String::class.java,
            )
        finalStatus shouldBe "Approved"

        val approvalCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM approvals WHERE review_id = :reviewId",
                MapSqlParameterSource("reviewId", created.reviewId),
                Int::class.java,
            )
        approvalCount shouldBe 2
    }

    @Test
    fun `rejectで却下するとRejectedへ遷移しcommentがapprovalsへ保存される`() {
        val (promptKey, semVer) = createInReviewPrompt()
        val (created, createdEvent) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 1, allowSelfApproval = false),
                context,
            )
        reviewCaseRepository.save(created, listOf(createdEvent))

        val loaded = reviewCaseRepository.findInReview(promptKey, semVer)!!
        val (rejected, rejectedEvent) =
            loaded.reject("user:reviewer", "not aligned with policy", context = context.copy(actor = "user:reviewer"))
        reviewCaseRepository.save(rejected, listOf(rejectedEvent))

        reviewCaseRepository.findInReview(promptKey, semVer) shouldBe null

        val row =
            jdbcTemplate.queryForObject(
                "SELECT status FROM review_cases WHERE review_id = :reviewId",
                MapSqlParameterSource("reviewId", created.reviewId),
                String::class.java,
            )
        row shouldBe "Rejected"

        val comment =
            jdbcTemplate.queryForObject(
                "SELECT comment FROM approvals WHERE review_id = :reviewId AND decision = 'REJECTED'",
                MapSqlParameterSource("reviewId", created.reviewId),
                String::class.java,
            )
        comment shouldBe "not aligned with policy"

        val eventTypes =
            jdbcTemplate.query(
                """
                SELECT event_type FROM domain_events
                WHERE aggregate_id = :reviewId AND aggregate_type = 'ReviewCase' ORDER BY sequence
                """.trimIndent(),
                MapSqlParameterSource("reviewId", created.reviewId),
            ) { rs, _ -> rs.getString("event_type") }
        eventTypes shouldBe listOf("PromptReviewRequested", "PromptRejected")
    }

    @Test
    fun `イベントはaggregateType ReviewCase としてdomain_eventsへ追記される`() {
        val (promptKey, semVer) = createInReviewPrompt()
        val (created, createdEvent) =
            ReviewCase.create(
                promptKey,
                semVer,
                ApprovalPolicy(requiredApprovals = 1, allowSelfApproval = true),
                context,
            )
        reviewCaseRepository.save(created, listOf(createdEvent))

        val (approved, approvedEvent) =
            created.approve(
                context.actor,
                null,
                allowSelfApproval = true,
                context = context,
            )
        reviewCaseRepository.save(approved, listOfNotNull(approvedEvent))

        val rows =
            jdbcTemplate.query(
                """
                SELECT sequence, event_type, aggregate_type
                FROM domain_events WHERE aggregate_id = :reviewId ORDER BY sequence
                """.trimIndent(),
                MapSqlParameterSource("reviewId", created.reviewId),
            ) { rs, _ -> Triple(rs.getLong("sequence"), rs.getString("event_type"), rs.getString("aggregate_type")) }

        rows shouldBe
            listOf(
                Triple(1L, "PromptReviewRequested", "ReviewCase"),
                Triple(2L, "PromptApproved", "ReviewCase"),
            )
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
