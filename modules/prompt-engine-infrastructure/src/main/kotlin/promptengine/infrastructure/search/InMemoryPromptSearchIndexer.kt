package promptengine.infrastructure.search

import org.slf4j.LoggerFactory
import promptengine.domain.prompt.PromptKey
import promptengine.domain.search.PromptSearchIndexer
import java.util.Collections

/**
 * [PromptSearchIndexer]のM1簡易実装（`docs/prompts/p10b.md`「Search Indexer は M1 では
 * 簡易実装でよい」、ADR-0026決定6）。
 *
 * OpenSearch等の外部検索基盤へは接続せず、インデックス対象keyをプロセス内に保持し
 * 構造化ログへ残すだけ。読み取り側の検索は引き続き
 * [promptengine.domain.prompt.PromptSearchRepository]のRDB実装
 * （`JdbcPromptSearchRepository`）が担っており、本クラスが未実装でも検索機能自体は動く。
 */
class InMemoryPromptSearchIndexer : PromptSearchIndexer {
    private val indexed = Collections.synchronizedSet(mutableSetOf<PromptKey>())

    override fun index(key: PromptKey) {
        indexed += key
        logger.info("prompt_search_indexed promptKey={}", key.value)
    }

    override fun remove(key: PromptKey) {
        indexed -= key
        logger.info("prompt_search_index_removed promptKey={}", key.value)
    }

    /** テスト・診断用に現在のインデックス内容のスナップショットを返す。 */
    fun snapshot(): Set<PromptKey> = synchronized(indexed) { indexed.toSet() }

    private companion object {
        val logger = LoggerFactory.getLogger(InMemoryPromptSearchIndexer::class.java)
    }
}
