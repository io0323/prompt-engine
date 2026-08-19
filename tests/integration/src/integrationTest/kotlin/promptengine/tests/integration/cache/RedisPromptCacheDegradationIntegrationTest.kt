package promptengine.tests.integration.cache

import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.application.pipeline.MergeStage
import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.event.EventContext
import promptengine.domain.fragment.Fragment
import promptengine.domain.fragment.FragmentDomainEvent
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
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
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * `RedisPromptCache`の縮退動作（NFR-001、ADR-0033追加決定e）をTestcontainersで検証する。
 *
 * ユーザー指摘: 「キャッシュ導入で可用性が下がっている」— M2-3以前はDBのみでPrompt取得が
 * 完結していたが、Redisへの依存を追加した結果、Redis障害時にPrompt取得そのものが失敗する
 * 状態になっていた（`RedisPromptCache`の各メソッドに例外処理が無かったため）。本テストは
 * 「Redisを止めた状態でもPrompt取得が成功する（遅くなるだけ）」ことを固定する回帰テストで、
 * 修正前（`get`/`put`/`invalidateByPrompt`が例外を素通しする実装）はこのテストが
 * `RedisConnectionException`で失敗していたことを確認済み。
 *
 * 1メソッドに集約する（Redis停止はコンテナ単位の不可逆操作のため、複数`@Test`に分けると
 * 実行順序に依存する不安定なテストになる）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisPromptCacheDegradationIntegrationTest {
    private lateinit var redisClient: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var commands: RedisCommands<String, String>
    private lateinit var metricsRegistry: SimpleMeterRegistry
    private lateinit var cache: RedisPromptCache

    private val promptKey = PromptKey("cache-degradation-it/plain")

    private object EmptyTemplateRepository : TemplateRepository {
        override fun findByKey(key: TemplateKey): Template? = null

        override fun save(
            template: Template,
            events: List<TemplateDomainEvent>,
        ): Template = error("not used")
    }

    private object EmptyFragmentRepository : FragmentRepository {
        override fun findByKey(key: FragmentKey): Fragment? = null

        override fun save(
            fragment: Fragment,
            events: List<FragmentDomainEvent>,
        ): Fragment = error("not used")
    }

    @BeforeAll
    fun setUp() {
        redisClient = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(REDIS_PORT)}")
        connection = redisClient.connect()
        commands = connection.sync()
        metricsRegistry = SimpleMeterRegistry()
        cache = RedisPromptCache(commands, jacksonMapper(), MicrometerMetricsRecorder(metricsRegistry))
    }

    @AfterAll
    fun tearDown() {
        runCatching { connection.close() }
        runCatching { redisClient.shutdown() }
    }

    private fun promptVersion(): PromptVersion {
        val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
        val source =
            """
            ---
            pe: "1"
            kind: prompt
            key: ${promptKey.value}
            name: Cache Degradation Fixture
            ---
            plain text with no includes or extends
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
            traceId = "trace-cache-degradation-it",
            promptVersion = promptVersion,
        )

    private fun jacksonMapper() = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    @Test
    fun `Redis停止後もMergeStage経由のPrompt取得は成功しget_put_invalidateByPromptは例外を投げずcache_degradation_totalを記録する`() {
        val compositionService = CompositionServiceImpl(EmptyTemplateRepository, EmptyFragmentRepository)
        val stage = MergeStage(compositionService, cache)
        val version = promptVersion()

        // 事前確認: Redisが生きている間は正常に動作する（キャッシュ書き込み含む）。
        val warmUp = stage.execute(pipelineContext(version))
        warmUp.compiled shouldBe stage.execute(pipelineContext(version)).compiled

        redis.stop()

        // Redis停止後もMergeStage.execute自体は例外を投げず、コンパイル結果を返す
        // （キャッシュ経由ではなく毎回コンパイルされるだけで、Prompt取得自体は成功する）。
        val afterStop = stage.execute(pipelineContext(version))
        afterStop.compiled shouldBe warmUp.compiled

        // RedisPromptCache単体でも、get/put/invalidateByPromptのいずれもRedis接続断時に
        // 例外を投げないことを確認する。
        val directKey = CacheKey(PromptKey("cache-degradation-it/direct"), VersionRef.Fixed(SemVer(1, 0, 0)))
        val compiled =
            CompiledPrompt(
                body = emptyList(),
                dependencies = emptyList(),
                variables = emptyList(),
                contextRequirements = emptyList(),
            )

        cache.get(directKey) shouldBe null
        cache.put(directKey, CachedItem(compiled), Duration.ofSeconds(30))
        cache.invalidateByPrompt(directKey.promptKey)

        // MergeStageのcache miss (warmUp/afterStopの2回目呼び出し以降は再compile) と
        // 直接呼び出し(get/put/invalidateByPrompt)の両方がcache_degradation_totalに反映される。
        metricsRegistry.get("cache_degradation_total").tag("operation", "GET").counter().count() shouldBe 2.0
        metricsRegistry.get("cache_degradation_total").tag("operation", "PUT").counter().count() shouldBe 2.0
        metricsRegistry.get("cache_degradation_total").tag("operation", "INVALIDATE").counter().count() shouldBe 1.0
    }

    private companion object {
        const val REDIS_PORT = 6379

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7").withExposedPorts(REDIS_PORT)
    }
}
