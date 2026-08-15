package promptengine.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.cache.PromptCache
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import java.time.Duration

/**
 * [PromptCache]のRedis実装（設計書§16拡張ポイント#9の既定実装、ADR-0033決定d）。
 *
 * キー構造は2種類:
 * - `pe:cache:compiled:{promptKey}:{versionRefセグメント}`: [CachedItem]（JSON、
 *   [compiledPromptObjectMapper]でシリアライズ）本体。[ttl]で自動失効する。
 * - `pe:cache:keys:{promptKey}`: 上記キーのうち、その[PromptKey]に属する全キーを
 *   追跡するRedis SET。[invalidateByPrompt]が`versionRef`を問わず該当Promptの
 *   全エントリを一括で削除するために使う（`KEYS`/`SCAN`によるパターン走査を避け、
 *   O(1)の集合検索で削除対象を求める）。
 */
class RedisPromptCache(
    private val commands: RedisCommands<String, String>,
    objectMapper: ObjectMapper,
) : PromptCache {
    private val mapper = compiledPromptObjectMapper(objectMapper)

    override fun get(key: CacheKey): CachedItem? {
        val json = commands.get(entryKey(key)) ?: return null
        val compiledPrompt = mapper.readValue(json, CompiledPrompt::class.java)
        return CachedItem(compiledPrompt)
    }

    override fun put(
        key: CacheKey,
        item: CachedItem,
        ttl: Duration,
    ) {
        val json = mapper.writeValueAsString(item.compiledPrompt)
        val entry = entryKey(key)
        commands.set(entry, json, SetArgs.Builder.px(ttl.toMillis()))
        val trackingKey = trackingKey(key.promptKey)
        commands.sadd(trackingKey, entry)
        commands.pexpire(trackingKey, ttl.toMillis())
    }

    override fun invalidateByPrompt(key: PromptKey) {
        val trackingKey = trackingKey(key)
        val entries = commands.smembers(trackingKey)
        if (entries.isNotEmpty()) {
            commands.del(*entries.toTypedArray())
        }
        commands.del(trackingKey)
    }

    private fun entryKey(key: CacheKey): String =
        "$COMPILED_PREFIX${key.promptKey.value}:${versionRefSegment(
            key.versionRef,
        )}"

    private fun trackingKey(promptKey: PromptKey): String = "$KEYS_PREFIX${promptKey.value}"

    private fun versionRefSegment(versionRef: VersionRef): String =
        when (versionRef) {
            is VersionRef.Fixed -> "fixed:${versionRef.semVer}"
            VersionRef.Latest -> "latest"
            is VersionRef.Alias -> "alias:${versionRef.name}"
        }

    private companion object {
        const val COMPILED_PREFIX = "pe:cache:compiled:"
        const val KEYS_PREFIX = "pe:cache:keys:"
    }
}
