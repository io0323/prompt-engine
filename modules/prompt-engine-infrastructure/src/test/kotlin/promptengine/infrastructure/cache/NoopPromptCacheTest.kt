package promptengine.infrastructure.cache

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.SemVer
import promptengine.domain.template.ast.TextNode
import java.time.Duration

/**
 * [NoopPromptCache]のテスト（ADR-0033、非productionでのRedis未接続フォールバック）。
 */
class NoopPromptCacheTest {
    private val cache = NoopPromptCache()
    private val key = CacheKey(PromptKey("noop-cache/example"), VersionRef.Fixed(SemVer(1, 0, 0)))
    private val item = CachedItem(minimalCompiledPrompt())

    @Test
    fun `putしても内部状態を持たないためgetは常にnullを返す`() {
        cache.put(key, item, Duration.ofSeconds(30))

        cache.get(key) shouldBe null
    }

    @Test
    fun `putを呼ばなくてもgetは常にnullを返す`() {
        cache.get(key) shouldBe null
    }

    @Test
    fun `invalidateByPromptを呼んでも例外を投げない`() {
        cache.put(key, item, Duration.ofSeconds(30))

        cache.invalidateByPrompt(key.promptKey)

        cache.get(key) shouldBe null
    }

    private fun minimalCompiledPrompt(): CompiledPrompt =
        CompiledPrompt(
            body = listOf(TextNode("hello")),
            dependencies = emptyList(),
            variables = emptyList(),
            contextRequirements = emptyList(),
        )
}
