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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.context.ContextRequirement
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.ExtendsRefApi
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.TemplateKey
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.VersionConflictException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * [EventStorePromptRepository] のTestcontainers(PostgreSQL 16)統合テスト（設計書§6.3）。
 *
 * 必須の3シナリオ + スナップショット動作確認:
 * - 保存→復元→内容一致（Draft/InReview/Approved/Published/Deprecated/Archivedの全状態を往復）
 * - イベント追記順序とsequenceの連番性
 * - 同時更新での楽観ロック衝突
 */
@OptIn(ExtendsRefApi::class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventStorePromptRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbcTemplate: NamedParameterJdbcTemplate
    private lateinit var repository: EventStorePromptRepository

    private val context =
        EventContext(
            actor = "user:integration-test",
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
        repository =
            EventStorePromptRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper(), snapshotThreshold = 2)
    }

    @AfterAll
    fun tearDown() {
        (dataSource as HikariDataSource).close()
    }

    @Test
    fun `保存したPromptは全状態(Draft InReview Approved Published Deprecated Archived)を往復しても内容が一致する`() {
        val key = uniqueKey()
        val v1 = SemVer(0, 1, 0)
        val v2 = SemVer(0, 2, 0)
        val variables =
            listOf(
                VariableDefinition(
                    name = "question",
                    type = VariableType.STRING,
                    source = VariableSource.STATIC,
                    required = true,
                    default = "N/A",
                    constraints = listOf("maxLength:200"),
                    sensitive = false,
                ),
                // sensitive=trueの変数はリテラルのdefaultを持てない（ADR-0007、
                // VariableDefinitionの不変条件）。defaultなしで往復することを検証する。
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
        val contextRequirements =
            listOf(ContextRequirement(scope = "user", required = listOf("userId"), optional = listOf("locale")))
        val extendsRef = ExtendsRef(TemplateKey("templates/base-assistant"), VersionRange.CaretMajor(2))

        // Draft（variables/contextRequirements/extends（key+range）のround-tripも合わせて検証する。ADR-0009）
        val (created, createdEvent) =
            Prompt.create(
                key,
                NewPromptVersion(v1, PromptContent("Answer: {{question}}"), variables, contextRequirements, extendsRef),
                context,
            )
        repository.save(created, listOf(createdEvent))
        var reloaded = repository.findByKey(key)!!
        reloaded.versions.single().state shouldBe LifecycleState.Draft
        reloaded.versions.single().content shouldBe PromptContent("Answer: {{question}}")
        reloaded.versions.single().variables shouldBe variables
        reloaded.versions.single().contextRequirements shouldBe contextRequirements
        reloaded.versions.single().extends shouldBe extendsRef

        // InReview
        repository.save(reloaded.submitForReview(v1, validationPassed = true))
        reloaded = repository.findByKey(key)!!
        reloaded.versions.single().state shouldBe LifecycleState.InReview

        // Approved
        repository.save(reloaded.approve(v1, approvalCount = 1, requiredApprovalCount = 1))
        reloaded = repository.findByKey(key)!!
        reloaded.versions.single().state shouldBe LifecycleState.Approved

        // Published
        val (publishedV1, publishV1Events) = reloaded.publish(v1, allDependenciesPublished = true, context)
        repository.save(publishedV1, publishV1Events)
        reloaded = repository.findByKey(key)!!
        reloaded.versions.single().state shouldBe LifecycleState.Published

        // v2をDraftで追加してPublishedまで進め、v1をDeprecated(SUPERSEDED)へsupersedeさせる
        val (withV2, v2CreatedEvent) =
            reloaded.newVersion(NewPromptVersion(v2, PromptContent("Answer(v2): {{question}}")), context)
        repository.save(withV2, listOf(v2CreatedEvent))
        reloaded = repository.findByKey(key)!!

        repository.save(reloaded.submitForReview(v2, validationPassed = true))
        reloaded = repository.findByKey(key)!!
        repository.save(reloaded.approve(v2, approvalCount = 1, requiredApprovalCount = 1))
        reloaded = repository.findByKey(key)!!

        val (publishedV2, publishV2Events) = reloaded.publish(v2, allDependenciesPublished = true, context)
        repository.save(publishedV2, publishV2Events)
        reloaded = repository.findByKey(key)!!

        val v1AfterSupersede = reloaded.versions.single { it.semVer == v1 }
        val v2AfterPublish = reloaded.versions.single { it.semVer == v2 }
        v1AfterSupersede.state shouldBe LifecycleState.Deprecated
        v2AfterPublish.state shouldBe LifecycleState.Published
        v2AfterPublish.content shouldBe PromptContent("Answer(v2): {{question}}")

        // Archived
        val (archived, archivedEvent) = reloaded.archive(v1, referencingClientCount = 0, force = false, context)
        repository.save(archived, listOf(archivedEvent))
        reloaded = repository.findByKey(key)!!

        val v1Final = reloaded.versions.single { it.semVer == v1 }
        val v2Final = reloaded.versions.single { it.semVer == v2 }
        v1Final.state shouldBe LifecycleState.Archived
        v2Final.state shouldBe LifecycleState.Published
        reloaded.key shouldBe key
    }

    @Test
    fun `イベントはaggregate単位でsequenceが1から連番で追記される`() {
        val key = uniqueKey()
        val v1 = SemVer(0, 1, 0)
        val v2 = SemVer(0, 2, 0)

        val (created, createdEvent) = Prompt.create(key, NewPromptVersion(v1, PromptContent("body")), context)
        repository.save(created, listOf(createdEvent))
        val loaded1 = repository.findByKey(key)!!

        val (withV2, v2CreatedEvent) = loaded1.newVersion(NewPromptVersion(v2, PromptContent("body v2")), context)
        repository.save(withV2, listOf(v2CreatedEvent))

        val promptId = findPromptId(key)
        val rows =
            jdbcTemplate.query(
                """
                SELECT sequence, event_type, aggregate_type, actor, trace_id
                FROM domain_events WHERE aggregate_id = :promptId ORDER BY sequence
                """.trimIndent(),
                MapSqlParameterSource("promptId", promptId),
            ) { rs, _ ->
                EventRow(
                    sequence = rs.getLong("sequence"),
                    eventType = rs.getString("event_type"),
                    aggregateType = rs.getString("aggregate_type"),
                    actor = rs.getString("actor"),
                    traceId = rs.getString("trace_id"),
                )
            }

        rows shouldBe
            listOf(
                EventRow(1L, "PromptCreated", "Prompt", context.actor, context.traceId),
                EventRow(2L, "PromptVersionCreated", "Prompt", context.actor, context.traceId),
            )
    }

    private data class EventRow(
        val sequence: Long,
        val eventType: String,
        val aggregateType: String,
        val actor: String,
        val traceId: String,
    )

    @Test
    fun `読んだ時点のrowVersionが古いままsaveすると楽観ロック衝突でVersionConflictExceptionを投げる`() {
        val key = uniqueKey()
        val v1 = SemVer(0, 1, 0)

        val (created, createdEvent) = Prompt.create(key, NewPromptVersion(v1, PromptContent("body")), context)
        repository.save(created, listOf(createdEvent))

        val readByFirstCaller = repository.findByKey(key)!!
        val readBySecondCaller = repository.findByKey(key)!!

        repository.save(readByFirstCaller.submitForReview(v1, validationPassed = true))

        shouldThrow<VersionConflictException> {
            repository.save(readBySecondCaller.submitForReview(v1, validationPassed = true))
        }
    }

    @Test
    fun `sequenceが閾値を超えるとprompt_snapshotsにスナップショットが保存される`() {
        val key = uniqueKey()
        val v1 = SemVer(0, 1, 0)

        val (created, createdEvent) = Prompt.create(key, NewPromptVersion(v1, PromptContent("body")), context)
        repository.save(created, listOf(createdEvent))
        val (withV2, v2Event) =
            repository.findByKey(key)!!.newVersion(NewPromptVersion(SemVer(0, 2, 0), PromptContent("body v2")), context)
        repository.save(withV2, listOf(v2Event))
        val (withV3, v3Event) =
            repository.findByKey(key)!!.newVersion(NewPromptVersion(SemVer(0, 3, 0), PromptContent("body v3")), context)
        repository.save(withV3, listOf(v3Event))

        // snapshotThreshold=2、イベントは3件（sequence 1,2,3）。しきい値判定は
        // 「直近スナップショット以降のsequenceがthreshold以上進んだら保存」なので、
        // sequence=2の時点で1件だけ保存され、sequence=3では(3-2=1<2)保存されない。
        // count>0のような弱い検証だと、しきい値判定が壊れて毎回保存されるようになっても
        // 検出できない。
        val promptId = findPromptId(key)
        val snapshotSequences =
            jdbcTemplate.query(
                "SELECT sequence FROM prompt_snapshots WHERE aggregate_id = :promptId ORDER BY sequence",
                MapSqlParameterSource("promptId", promptId),
            ) { rs, _ -> rs.getLong("sequence") }

        snapshotSequences shouldBe listOf(2L)
    }

    private fun findPromptId(key: PromptKey): UUID =
        jdbcTemplate.queryForObject(
            "SELECT prompt_id FROM prompts WHERE prompt_key = :promptKey",
            MapSqlParameterSource("promptKey", key.value),
            UUID::class.java,
        )!!

    private fun uniqueKey(): PromptKey = PromptKey("integration-test/${UUID.randomUUID()}")

    private companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }
}
