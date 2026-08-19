package promptengine.tests.integration.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.application.pipeline.MergeStage
import promptengine.domain.cache.PromptCache
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.CompositionService
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.event.EventContext
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentContent
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.NewFragmentVersion
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.Template
import promptengine.domain.template.TemplateDomainEvent
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import promptengine.engine.compiler.CompositionServiceImpl
import promptengine.infrastructure.cache.RedisPromptCache
import promptengine.infrastructure.observability.MicrometerMetricsRecorder
import promptengine.infrastructure.persistence.JdbcFragmentRepository
import java.math.BigDecimal
import java.time.Instant
import javax.sql.DataSource

/**
 * Fragment publish後にPromptのCompiledPromptキャッシュが古い内容のまま動き続けないことを、
 * 実際のRedis・実際のCompositionServiceImpl・実際のJdbcFragmentRepositoryを使って
 * 「内容」で検証する統合テスト（Issue #77、ADR-0033、M2-3プロンプトの最重要観点）。
 *
 * 「キーが一致すること」ではなく、以下を全て内容で確認する:
 * 1. 同一入力の2回目呼び出しはCompositionService.compileを再実行しない（キャッシュヒット）
 * 2. Fragmentを新Version（^1にマッチ）でpublishしても、無効化前はキャッシュが
 *    古いFragment内容のまま返り続ける（無効化漏れが起きたときに何が起きるかを固定する）
 * 3. 無効化後は再コンパイルされ、新しいFragment内容を反映した結果になる
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheContentInvalidationIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var fragmentRepository: JdbcFragmentRepository
    private lateinit var redisClient: RedisClient
    private lateinit var redisConnection: StatefulRedisConnection<String, String>
    private lateinit var promptCache: PromptCache
    private lateinit var countingCompositionService: CountingCompositionService
    private lateinit var mergeStage: MergeStage

    private val fragmentKey = FragmentKey("cache-it/notice")
    private val promptKey = PromptKey("cache-it/uses-fragment")
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private class CountingCompositionService(private val delegate: CompositionService) : CompositionService {
        var callCount: Int = 0
            private set

        override fun compile(
            promptKey: PromptKey,
            promptVersion: PromptVersion,
            mode: CompositionMode,
        ): CompiledPrompt {
            callCount++
            return delegate.compile(promptKey, promptVersion, mode)
        }
    }

    private object EmptyTemplateRepository : TemplateRepository {
        override fun findByKey(key: TemplateKey): Template? = null

        override fun save(
            template: Template,
            events: List<TemplateDomainEvent>,
        ): Template = error("not used")
    }

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
        fragmentRepository = JdbcFragmentRepository(jdbcTemplate, transactionTemplate, jacksonObjectMapper())

        redisClient = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(REDIS_PORT)}")
        redisConnection = redisClient.connect()
        val commands: RedisCommands<String, String> = redisConnection.sync()
        promptCache =
            RedisPromptCache(commands, jacksonObjectMapper(), MicrometerMetricsRecorder(SimpleMeterRegistry()))

        countingCompositionService =
            CountingCompositionService(CompositionServiceImpl(EmptyTemplateRepository, fragmentRepository))
        mergeStage = MergeStage(countingCompositionService, promptCache)
    }

    @AfterAll
    fun tearDown() {
        redisConnection.close()
        redisClient.shutdown()
        (dataSource as HikariDataSource).close()
    }

    private fun publishFragment(
        semVer: SemVer,
        bodyText: String,
    ) {
        val source = "---\npe: \"1\"\nkind: fragment\nkey: ${fragmentKey.value}\n---\n$bodyText"
        val existing = fragmentRepository.findByKey(fragmentKey)
        val (created, createdEvent) =
            if (existing == null) {
                Fragment.create(fragmentKey, NewFragmentVersion(semVer, FragmentContent(source)), context)
            } else {
                existing.newVersion(NewFragmentVersion(semVer, FragmentContent(source)), context)
            }
        val (published, publishedEvent) = created.publish(semVer, context)
        fragmentRepository.save(published, listOf(createdEvent, publishedEvent))
    }

    private fun promptVersion(): PromptVersion {
        val source =
            """
            ---
            pe: "1"
            kind: prompt
            key: ${promptKey.value}
            name: Cache Invalidation Fixture
            ---
            {{#block user}}before {{> ${fragmentKey.value}@^1 }} after{{/block}}
            """.trimIndent()
        val newVersion = NewPromptVersion(semVer = SemVer(1, 0, 0), content = PromptContent(source))
        return Prompt.create(promptKey, newVersion, context).first.versions.first()
    }

    private fun pipelineContext(promptVersion: PromptVersion): PipelineContext =
        PipelineContext(
            request =
                PipelineRequest(
                    promptKey = promptKey,
                    versionRef = VersionRef.Fixed(SemVer(1, 0, 0)),
                    variableResolution = PromptRequest(),
                    modelProfile =
                        ModelProfile(
                            maxContextTokens = TokenCount(1_000),
                            tokenizerId = "test-tokenizer",
                            costPerToken = Cost(BigDecimal.ZERO),
                        ),
                    budget = TokenCount(1_000),
                ),
            mode = PipelineMode.RENDER_ONLY,
            traceId = "trace-cache-it",
            promptVersion = promptVersion,
        )

    private fun resolvedFragmentVersion(compiled: CompiledPrompt): SemVer =
        compiled.dependencies
            .filterIsInstance<ResolvedDependency.FragmentDependency>()
            .single { it.key == fragmentKey }
            .resolvedVersion

    @Test
    fun `キャッシュヒット時は再compileされず 無効化前はFragment再publish後も古い内容のまま 無効化後は新しい内容になる`() {
        publishFragment(SemVer(1, 0, 0), "original notice")
        val version = promptVersion()

        // 1回目: compileが実際に走り、Fragment 1.0.0の内容を反映する。
        val first = mergeStage.execute(pipelineContext(version))
        countingCompositionService.callCount shouldBe 1
        resolvedFragmentVersion(first.compiled!!) shouldBe SemVer(1, 0, 0)

        // 2回目（同一キー）: キャッシュヒットのためcompileは再実行されない。
        val second = mergeStage.execute(pipelineContext(version))
        countingCompositionService.callCount shouldBe 1
        resolvedFragmentVersion(second.compiled!!) shouldBe SemVer(1, 0, 0)

        // Fragmentを1.1.0（^1にマッチ）で新規publishする。
        publishFragment(SemVer(1, 1, 0), "updated notice")

        // 無効化前: 無効化漏れが起きた場合に何が起きるかを固定する。
        // compileは呼ばれず、古い1.0.0ベースの内容がそのまま返り続ける
        // （「無効化漏れ＝テストは通るが古い内容で動き続ける」という最悪の形をここで再現する）。
        val staleRead = mergeStage.execute(pipelineContext(version))
        countingCompositionService.callCount shouldBe 1
        resolvedFragmentVersion(staleRead.compiled!!) shouldBe SemVer(1, 0, 0)

        // 無効化: CacheInvalidationSubscriberが実際に呼ぶのと同じAPI呼び出し
        // （無効化対象の特定ロジック自体はCacheAndSearchSubscriberTestで別途検証済み）。
        promptCache.invalidateByPrompt(promptKey)

        // 無効化後: 再compileされ、^1が拾う最新版（1.1.0）の内容を反映する。
        val fresh = mergeStage.execute(pipelineContext(version))
        countingCompositionService.callCount shouldBe 2
        resolvedFragmentVersion(fresh.compiled!!) shouldBe SemVer(1, 1, 0)
    }

    private companion object {
        const val REDIS_PORT = 6379

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7").withExposedPorts(REDIS_PORT)
    }
}
