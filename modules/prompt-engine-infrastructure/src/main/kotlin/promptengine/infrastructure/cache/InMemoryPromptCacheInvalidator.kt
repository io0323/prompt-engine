package promptengine.infrastructure.cache

import org.slf4j.LoggerFactory
import promptengine.domain.cache.PromptCacheInvalidator
import promptengine.domain.prompt.PromptKey
import java.util.Collections

/**
 * [PromptCacheInvalidator]のM1実装（ADR-0026決定6）。
 *
 * PE内にRender結果／Composition結果の永続キャッシュ実装がまだ存在しない（設計書§2.14の
 * Redisキャッシュは未実装）ため、無効化要求を構造化ログへ残し、プロセス内に記録するだけの
 * 最小実装とする。**購読とポート呼び出しの配線自体は本物**であり、実キャッシュを入れる際は
 * 本クラスを差し替えるだけで済む。
 */
class InMemoryPromptCacheInvalidator : PromptCacheInvalidator {
    private val invalidated = Collections.synchronizedList(mutableListOf<PromptKey>())

    override fun invalidateByPrompt(key: PromptKey) {
        invalidated += key
        logger.info("prompt_cache_invalidated promptKey={}", key.value)
    }

    /**
     * テスト・診断用に無効化済みkeyのスナップショットを返す。
     * [Collections.synchronizedList]は個々のメソッド呼出しのみ同期するため、イテレーション
     * 全体を`synchronized`で囲む（`InMemoryEventBusAdapter.snapshot`と同じ理由）。
     */
    fun snapshot(): List<PromptKey> = synchronized(invalidated) { invalidated.toList() }

    private companion object {
        val logger = LoggerFactory.getLogger(InMemoryPromptCacheInvalidator::class.java)
    }
}
