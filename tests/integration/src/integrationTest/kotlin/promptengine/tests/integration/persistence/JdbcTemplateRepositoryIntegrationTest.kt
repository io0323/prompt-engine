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
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.NewTemplateVersion
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateContent
import promptengine.domain.template.TemplateKey
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import promptengine.infrastructure.persistence.JdbcTemplateRepository
import promptengine.infrastructure.persistence.TemplateVersionConflictException
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcTemplateRepository] のTestcontainers(PostgreSQL 16)統合テスト（ADR-0008）。
 *
 * P2（[promptengine.infrastructure.persistence.EventStorePromptRepository]）の
 * 復元経路パターン（Memento + `@PersistenceApi`）が2つ目のAggregateでも通用するかを
 * 検証する。Domain Event/Outbox/Snapshotは対象外（ADR-0008）。
 */
@OptIn(ExtendsRefApi::class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcTemplateRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var repository: JdbcTemplateRepository

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
        repository = JdbcTemplateRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun `保存したTemplateは全状態 Draft Published Archived を往復しても内容が一致する`() {
        val key = uniqueKey()
        val extendsRef = ExtendsRef(TemplateKey("shared/base"), VersionRange.CaretMajor(2))
        val v1 = SemVer(0, 1, 0)
        val v2 = SemVer(0, 2, 0)
        val variables =
            listOf(
                VariableDefinition(
                    name = "tone",
                    type = VariableType.STRING,
                    source = VariableSource.STATIC,
                    required = true,
                    default = "polite",
                    constraints = listOf("enum:polite,casual"),
                    sensitive = false,
                ),
                // sensitive=trueの変数はリテラルのdefaultを持てない（ADR-0007、Template/Fragmentにも適用）。
                VariableDefinition(
                    name = "apiKeyRef",
                    type = VariableType.STRING,
                    source = VariableSource.SECRET,
                    required = true,
                    default = null,
                    constraints = emptyList(),
                    sensitive = true,
                ),
            )

        // Draft（variables/extends（key+range）のround-tripも合わせて検証する。ADR-0009）
        val created =
            Template.create(key, NewTemplateVersion(v1, TemplateContent("Hello {{name}}"), variables, extendsRef))
        repository.save(created)
        var reloaded = repository.findByKey(key)!!
        reloaded.versions.single().content shouldBe TemplateContent("Hello {{name}}")
        reloaded.versions.single().variables shouldBe variables
        reloaded.versions.single().extends shouldBe extendsRef
        reloaded.versions.single().state shouldBe PublicationState.Draft

        // Published
        repository.save(reloaded.publish(v1))
        reloaded = repository.findByKey(key)!!
        reloaded.versions.single().state shouldBe PublicationState.Published

        // v2をDraftで追加し、v1はArchivedへ進める（複数Versionの共存を検証、§15.4）
        val withV2 = reloaded.newVersion(NewTemplateVersion(v2, TemplateContent("Hello v2 {{name}}")))
        repository.save(withV2)
        reloaded = repository.findByKey(key)!!
        val archived = reloaded.archive(v1)
        repository.save(archived)
        reloaded = repository.findByKey(key)!!

        val v1Final = reloaded.versions.single { it.semVer == v1 }
        val v2Final = reloaded.versions.single { it.semVer == v2 }
        v1Final.state shouldBe PublicationState.Archived
        v2Final.state shouldBe PublicationState.Draft
        v2Final.content shouldBe TemplateContent("Hello v2 {{name}}")
        reloaded.key shouldBe key
    }

    @Test
    fun `存在しないkeyを検索するとnullを返す`() {
        repository.findByKey(uniqueKey()) shouldBe null
    }

    @Test
    fun `読んだ時点のrowVersionが古いままsaveすると楽観ロック衝突でTemplateVersionConflictExceptionを投げる`() {
        val key = uniqueKey()
        val v1 = SemVer(0, 1, 0)

        val created = Template.create(key, NewTemplateVersion(v1, TemplateContent("body")))
        repository.save(created)

        val readByFirstCaller = repository.findByKey(key)!!
        val readBySecondCaller = repository.findByKey(key)!!

        repository.save(readByFirstCaller.publish(v1))

        shouldThrow<TemplateVersionConflictException> {
            repository.save(readBySecondCaller.publish(v1))
        }
    }

    private fun uniqueKey(): TemplateKey = TemplateKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
