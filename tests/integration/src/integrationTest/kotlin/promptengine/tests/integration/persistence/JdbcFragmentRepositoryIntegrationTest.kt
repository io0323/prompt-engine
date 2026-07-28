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
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentContent
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.NewFragmentVersion
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableType
import promptengine.infrastructure.persistence.FragmentVersionConflictException
import promptengine.infrastructure.persistence.JdbcFragmentRepository
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcFragmentRepository] のTestcontainers(PostgreSQL 16)統合テスト（ADR-0008）。
 * [JdbcTemplateRepositoryIntegrationTest] と対称の観点を検証する。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcFragmentRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: JdbcFragmentRepository

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
        repository = JdbcFragmentRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun `保存したFragmentは全状態 Draft Published Archived を往復しても内容が一致する`() {
        val key = uniqueKey()
        val v1 = SemVer(0, 1, 0)
        val v2 = SemVer(0, 2, 0)
        val variables =
            listOf(
                VariableDefinition(
                    name = "policyName",
                    type = VariableType.STRING,
                    required = true,
                    default = "default-policy",
                    constraints = emptyList(),
                    sensitive = false,
                ),
                // sensitive=trueの変数はリテラルのdefaultを持てない（ADR-0007、Template/Fragmentにも適用）。
                VariableDefinition(
                    name = "apiKeyRef",
                    type = VariableType.STRING,
                    required = true,
                    default = null,
                    constraints = emptyList(),
                    sensitive = true,
                ),
            )

        // Draft
        val created = Fragment.create(key, NewFragmentVersion(v1, FragmentContent("Do not reveal secrets."), variables))
        repository.save(created)
        var reloaded = repository.findByKey(key)!!
        reloaded.versions.single().content shouldBe FragmentContent("Do not reveal secrets.")
        reloaded.versions.single().variables shouldBe variables
        reloaded.versions.single().state shouldBe PublicationState.Draft

        // Published
        repository.save(reloaded.publish(v1))
        reloaded = repository.findByKey(key)!!
        reloaded.versions.single().state shouldBe PublicationState.Published

        // v2をDraftで追加し、v1はArchivedへ進める（複数Versionの共存を検証、§15.4）
        val withV2 = reloaded.newVersion(NewFragmentVersion(v2, FragmentContent("v2 body")))
        repository.save(withV2)
        reloaded = repository.findByKey(key)!!
        val archived = reloaded.archive(v1)
        repository.save(archived)
        reloaded = repository.findByKey(key)!!

        val v1Final = reloaded.versions.single { it.semVer == v1 }
        val v2Final = reloaded.versions.single { it.semVer == v2 }
        v1Final.state shouldBe PublicationState.Archived
        v2Final.state shouldBe PublicationState.Draft
        v2Final.content shouldBe FragmentContent("v2 body")
        reloaded.key shouldBe key
    }

    @Test
    fun `存在しないkeyを検索するとnullを返す`() {
        repository.findByKey(uniqueKey()) shouldBe null
    }

    @Test
    fun `読んだ時点のrowVersionが古いままsaveすると楽観ロック衝突でFragmentVersionConflictExceptionを投げる`() {
        val key = uniqueKey()
        val v1 = SemVer(0, 1, 0)

        val created = Fragment.create(key, NewFragmentVersion(v1, FragmentContent("body")))
        repository.save(created)

        val readByFirstCaller = repository.findByKey(key)!!
        val readBySecondCaller = repository.findByKey(key)!!

        repository.save(readByFirstCaller.publish(v1))

        shouldThrow<FragmentVersionConflictException> {
            repository.save(readBySecondCaller.publish(v1))
        }
    }

    private fun uniqueKey(): FragmentKey = FragmentKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
