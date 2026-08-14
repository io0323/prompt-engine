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
import promptengine.domain.governance.ApprovalPolicy
import promptengine.domain.governance.ReviewCase
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcReviewCaseRepository
import promptengine.infrastructure.persistence.VersionConflictException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0032決定1（approve確定時、ReviewCaseとPromptを同一トランザクションで更新する）が
 * 前提とする、`Prompt.rowVersion`楽観ロックの並行性保証をTestcontainers(PostgreSQL 16)で
 * 検証する。
 *
 * `approve`はReviewCase保存に加えて`promptRepository.save`も呼ぶため（[ApproveHandler]
 * 参照）、Prompt側の他の操作（`publish`/`newVersion`等）と同じ`prompts.row_version`
 * 楽観ロックの対象になる。両者が同じ`Prompt`行に対して古い`rowVersion`を前提に競合した場合、
 * 既存の`EventStorePromptRepositoryIntegrationTest`が確立した「2回読み、1回目をsave、
 * 2回目（stale）をsaveしてVersionConflictExceptionを確認する」という単一スレッド上の
 * 疑似競合パターン（読者が2人いる状況を、実際のスレッド/コネクションを増やさずに
 * 決定論的に再現する）を、approve（本ADRで新設したPrompt保存経路）とnewVersion
 * （既存の保存経路）の組で再現する。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewCaseConcurrencyIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var promptRepository: EventStorePromptRepository
    private lateinit var reviewCaseRepository: JdbcReviewCaseRepository

    private val context =
        EventContext(actor = "user:author", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

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
        promptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
        reviewCaseRepository = JdbcReviewCaseRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun `approveとnewVersionが同じrowVersionを前提に競合すると片方がVERSION_CONFLICTになり不整合な状態が残らない`() {
        val key = PromptKey("integration-test/${UUID.randomUUID()}")
        val v1 = SemVer(0, 1, 0)
        val v2 = SemVer(0, 2, 0)

        val (created, createdEvent) = Prompt.create(key, NewPromptVersion(v1, PromptContent("body")), context)
        promptRepository.save(created, listOf(createdEvent))
        promptRepository.save(promptRepository.findByKey(key)!!.submitForReview(v1, validationPassed = true))
        val (reviewCase, reviewCaseEvent) =
            ReviewCase.create(key, v1, ApprovalPolicy(requiredApprovals = 1, allowSelfApproval = true), context)
        reviewCaseRepository.save(reviewCase, listOf(reviewCaseEvent))

        // 2人の読者が同じrowVersionを読んだ状況を、実スレッドを使わず決定論的に再現する
        // （EventStorePromptRepositoryIntegrationTestの既存パターンを踏襲）。
        val readByApproveFlow = promptRepository.findByKey(key)!!
        val readByNewVersionFlow = promptRepository.findByKey(key)!!
        readByApproveFlow.rowVersion shouldBe readByNewVersionFlow.rowVersion

        // approve側が先にcommitする（ApproveHandlerがReviewCase保存後にPrompt.approve+save
        // を呼ぶのと同じ順序。ADR-0032決定1）。
        val approvedPrompt = readByApproveFlow.approve(v1, approvalCount = 1, requiredApprovalCount = 1)
        promptRepository.save(approvedPrompt)

        // newVersion側は古いrowVersionのまま保存を試み、楽観ロック衝突で失敗する。
        val (withV2, _) = readByNewVersionFlow.newVersion(NewPromptVersion(v2, PromptContent("body v2")), context)
        shouldThrow<VersionConflictException> {
            promptRepository.save(withV2)
        }

        // 不整合な状態が残らないこと: approveの効果（v1がApproved）だけが残り、
        // newVersionの効果（v2の追加）は一切残らない（部分的に混ざった状態にならない）。
        val reloaded = promptRepository.findByKey(key)!!
        reloaded.versions.map { it.semVer } shouldBe listOf(v1)
        reloaded.versions.single().state shouldBe LifecycleState.Approved
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
