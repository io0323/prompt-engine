package promptengine.tests.integration.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.context.ContextRequirement
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.OutputDeclaration
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.FilterCall
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.NumberLiteral
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.PlaceholderMode
import promptengine.domain.validation.ValidationSettings
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource
import promptengine.domain.variable.VariableType
import promptengine.infrastructure.cache.RedisPromptCache
import java.time.Duration

/**
 * [RedisPromptCache]のTestcontainers(Redis 7)統合テスト（Issue #77、ADR-0033決定d）。
 *
 * `compose.yaml`にredisサービスが定義済みだったが11フェーズを通じて一度も接続実績が
 * 無かった（ユーザー指摘）ため、実際にコンテナを起動して読み書きすることを検証する。
 * 「キーが一致すること」ではなく「取り出した内容が元の値と一致すること」を確認する
 * （ネストしたsealed型のJacksonポリモーフィズム往復が実際に機能しているかの検証）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisPromptCacheIntegrationTest {
    private lateinit var redisClient: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var commands: RedisCommands<String, String>
    private lateinit var cache: RedisPromptCache

    @BeforeAll
    fun setUp() {
        redisClient = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(REDIS_PORT)}")
        connection = redisClient.connect()
        commands = connection.sync()
        cache = RedisPromptCache(commands, jacksonObjectMapper())
    }

    @AfterAll
    fun tearDown() {
        connection.close()
        redisClient.shutdown()
    }

    /** extends/import/include・条件分岐・繰り返し・macroを一通り含む、実運用に近い複雑な木。 */
    private fun richCompiledPrompt(): CompiledPrompt =
        CompiledPrompt(
            body =
                listOf(
                    TextNode("plain text"),
                    BlockNode(
                        BlockRole.SYSTEM,
                        listOf(
                            IfNode(
                                condition = Expression(PropertyRef(listOf("user", "isAdmin"))),
                                thenBranch =
                                    listOf(
                                        ExprNode(
                                            Expression(
                                                PropertyRef(listOf("user", "name")),
                                                filters = listOf(FilterCall("upper")),
                                            ),
                                        ),
                                    ),
                                elseBranch = listOf(TextNode("guest")),
                            ),
                            EachNode(
                                iterable = Expression(PropertyRef(listOf("items"))),
                                itemName = "item",
                                body =
                                    listOf(
                                        ExprNode(Expression(PropertyRef(listOf("item", "label")))),
                                        ExprNode(Expression(NumberLiteral(1.5))),
                                    ),
                            ),
                            IncludeNode("fragments/shared-notice", versionRange = "^2", bindings = emptyMap()),
                            MacroCallNode(
                                "bulletList",
                                arguments = mapOf("items" to Expression(PropertyRef(listOf("items")))),
                            ),
                        ),
                    ),
                ),
            dependencies =
                listOf(
                    ResolvedDependency.TemplateDependency(
                        key = TemplateKey("shared/base"),
                        requestedRange = VersionRange.CaretMajor(2),
                        resolvedVersion = SemVer(2, 3, 0),
                        status = PublicationState.Published,
                        contentHash = "a".repeat(64),
                    ),
                    ResolvedDependency.FragmentDependency(
                        key = FragmentKey("fragments/shared-notice"),
                        requestedRange = VersionRange.Exact(SemVer(1, 0, 0)),
                        resolvedVersion = SemVer(1, 0, 0),
                        status = PublicationState.Published,
                        contentHash = "b".repeat(64),
                    ),
                    ResolvedDependency.TemplateDependency(
                        key = TemplateKey("shared/latest-ref"),
                        requestedRange = VersionRange.Latest,
                        resolvedVersion = SemVer(9, 9, 9),
                        status = PublicationState.Published,
                        contentHash = "c".repeat(64),
                    ),
                ),
            variables =
                listOf(
                    VariableDefinition(
                        name = "userName",
                        type = VariableType.STRING,
                        source = VariableSource.RUNTIME,
                        required = true,
                    ),
                ),
            contextRequirements = listOf(ContextRequirement(scope = "application", required = listOf("serviceName"))),
            validation = ValidationSettings(maxLength = 4000, placeholders = PlaceholderMode.STRICT),
            output = OutputDeclaration(format = OutputFormat.JSON, schemaRef = "schemas/answer.json"),
        )

    @Test
    fun `putしたCompiledPromptをgetで取り出すと入れ子構造を含め内容が完全に一致する`() {
        val key = CacheKey(PromptKey("cache-it/rich"), VersionRef.Fixed(SemVer(1, 0, 0)))
        val original = richCompiledPrompt()

        cache.put(key, CachedItem(original), Duration.ofSeconds(30))
        val fetched = cache.get(key)

        fetched shouldBe CachedItem(original)
    }

    @Test
    fun `putされていないキーはgetでnullを返す`() {
        val key = CacheKey(PromptKey("cache-it/never-put"), VersionRef.Latest)

        cache.get(key) shouldBe null
    }

    @Test
    fun `TTLを過ぎたエントリはgetでnullを返す`() {
        val key = CacheKey(PromptKey("cache-it/ttl"), VersionRef.Fixed(SemVer(1, 0, 0)))
        cache.put(key, CachedItem(richCompiledPrompt()), Duration.ofMillis(200))

        Thread.sleep(500)

        cache.get(key) shouldBe null
    }

    @Test
    fun `invalidateByPromptは対象PromptKeyの全versionRefエントリだけを削除し他のPromptには影響しない`() {
        val targetPrompt = PromptKey("cache-it/target")
        val otherPrompt = PromptKey("cache-it/other")
        val fixedKey = CacheKey(targetPrompt, VersionRef.Fixed(SemVer(1, 0, 0)))
        val latestKey = CacheKey(targetPrompt, VersionRef.Latest)
        val aliasKey = CacheKey(targetPrompt, VersionRef.Alias("stable"))
        val otherKey = CacheKey(otherPrompt, VersionRef.Fixed(SemVer(1, 0, 0)))
        val ttl = Duration.ofSeconds(30)
        listOf(fixedKey, latestKey, aliasKey).forEach { cache.put(it, CachedItem(richCompiledPrompt()), ttl) }
        val otherContent = richCompiledPrompt().copy(variables = emptyList())
        cache.put(otherKey, CachedItem(otherContent), ttl)

        cache.invalidateByPrompt(targetPrompt)

        // 対象Promptは3つのversionRefすべてが落ちている（内容が読めなくなっている）ことを確認する。
        cache.get(fixedKey) shouldBe null
        cache.get(latestKey) shouldBe null
        cache.get(aliasKey) shouldBe null
        // 無関係なPromptのエントリは中身がそのまま残っていることを内容で確認する
        // （キーが存在するかだけでなく、無効化時に誤って上書き・破損していないか）。
        cache.get(otherKey) shouldBe CachedItem(otherContent)
    }

    private companion object {
        const val REDIS_PORT = 6379

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7").withExposedPorts(REDIS_PORT)
    }
}
