package promptengine.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.cache.PromptCache
import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.observability.CacheOperation
import promptengine.domain.observability.MetricsRecorder
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import java.time.Duration

/**
 * [PromptCache]のRedis実装（設計書§16拡張ポイント#9の既定実装、ADR-0033決定d・追加決定e）。
 *
 * キー構造は2種類:
 * - `pe:cache:compiled:{promptKey}:{versionRefセグメント}`: [CachedItem]（JSON、
 *   [compiledPromptObjectMapper]でシリアライズ）本体。[ttl]で自動失効する。
 * - `pe:cache:keys:{promptKey}`: 上記キーのうち、その[PromptKey]に属する全キーを
 *   追跡するRedis SET。[invalidateByPrompt]が`versionRef`を問わず該当Promptの
 *   全エントリを一括で削除するために使う（`KEYS`/`SCAN`によるパターン走査を避け、
 *   O(1)の集合検索で削除対象を求める）。
 *
 * ## 縮退動作（NFR-001、ADR-0033追加決定e）
 * 全メソッドがRedisへの到達不能・タイムアウト等の例外を捕捉し、[PromptCache]のKDoc通りに
 * 縮退する（呼出元へ例外を伝播させない）。キャッシュはあくまで性能最適化であり、
 * 「無くても動く」ことが前提（NFR-001「Read系はキャッシュで縮退継続」）。
 * `get`/`put`の失敗は[LOGGER]にWARNで記録するのみだが、`invalidateByPrompt`の失敗は
 * 古い内容が生き残り続けるという質的に異なる損害を伴うためERRORで記録する。
 * いずれも[metricsRecorder]で検知可能にする。
 */
class RedisPromptCache(
    private val commands: RedisCommands<String, String>,
    objectMapper: ObjectMapper,
    private val metricsRecorder: MetricsRecorder,
) : PromptCache {
    private val mapper = compiledPromptObjectMapper(objectMapper)

    override fun get(key: CacheKey): CachedItem? =
        runCatching {
            commands.get(entryKey(key))?.let { json -> CachedItem(mapper.readValue(json, CompiledPrompt::class.java)) }
        }.getOrElse { cause ->
            metricsRecorder.incrementCacheDegradation(CacheOperation.GET)
            LOGGER.warn("cache_degraded operation=GET promptKey={} cause={}", key.promptKey.value, cause.toString())
            null
        }

    override fun put(
        key: CacheKey,
        item: CachedItem,
        ttl: Duration,
    ) {
        runCatching {
            val json = mapper.writeValueAsString(item.compiledPrompt)
            val entry = entryKey(key)
            commands.set(entry, json, SetArgs.Builder.px(ttl.toMillis()))
            val trackingKey = trackingKey(key.promptKey)
            commands.sadd(trackingKey, entry)
            commands.pexpire(trackingKey, ttl.toMillis())
        }.onFailure { cause ->
            metricsRecorder.incrementCacheDegradation(CacheOperation.PUT)
            LOGGER.warn("cache_degraded operation=PUT promptKey={} cause={}", key.promptKey.value, cause.toString())
        }
    }

    override fun invalidateByPrompt(key: PromptKey) {
        runCatching {
            val trackingKey = trackingKey(key)
            val entries = commands.smembers(trackingKey)
            if (entries.isNotEmpty()) {
                commands.del(*entries.toTypedArray())
            }
            commands.del(trackingKey)
        }.onFailure { cause ->
            metricsRecorder.incrementCacheDegradation(CacheOperation.INVALIDATE)
            LOGGER.error(
                "cache_degraded operation=INVALIDATE promptKey={} cause={} " +
                    "note=stale_entries_may_survive_until_ttl",
                key.value,
                cause.toString(),
            )
        }
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
        val LOGGER = LoggerFactory.getLogger(RedisPromptCache::class.java)
    }
}
