package promptengine.application.pipeline

import promptengine.domain.cache.CacheKey
import promptengine.domain.cache.CachedItem
import promptengine.domain.cache.PromptCache
import promptengine.domain.prompt.PromptKey
import java.time.Duration

/**
 * [PromptCache]のテスト用記録実装。`MergeStageTest`/`PipelineOrchestratorTest`/
 * `PipelineStageGuardsTest`で共用するため、単一ファイルの公開クラスとして定義する
 * （[RecordingMetricsRecorder]と同じ方針）。
 */
class RecordingPromptCache : PromptCache {
    private val store = mutableMapOf<CacheKey, CachedItem>()
    val getCalls = mutableListOf<CacheKey>()
    val putCalls = mutableListOf<Pair<CacheKey, Duration>>()
    val invalidated = mutableListOf<PromptKey>()

    override fun get(key: CacheKey): CachedItem? {
        getCalls += key
        return store[key]
    }

    override fun put(
        key: CacheKey,
        item: CachedItem,
        ttl: Duration,
    ) {
        putCalls += key to ttl
        store[key] = item
    }

    override fun invalidateByPrompt(key: PromptKey) {
        invalidated += key
        store.keys.filter { it.promptKey == key }.forEach { store.remove(it) }
    }
}
