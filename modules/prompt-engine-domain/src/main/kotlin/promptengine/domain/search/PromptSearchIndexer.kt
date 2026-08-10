package promptengine.domain.search

import promptengine.domain.prompt.PromptKey

/**
 * 検索インデックスの更新ポート（設計書§2.14 Search Indexer、ADR-0026決定6）。
 *
 * 読み取り側の[promptengine.domain.prompt.PromptSearchRepository]（RDBのLIKE検索）とは
 * 別の関心事。`pe.prompt`トピックを購読する`SearchIndexSubscriber`
 * （`prompt-engine-infrastructure`）が、Promptの内容・状態が変化したイベントで呼ぶ。
 *
 * M1は簡易実装でよい（`docs/prompts/p10b.md`「Search Indexer は M1 では簡易実装でよい」）。
 * OpenSearch等の外部検索基盤への接続は行わず、購読と呼出しの配線のみを本物にしておく。
 */
interface PromptSearchIndexer {
    /** [key]のインデックスを最新化する。 */
    fun index(key: PromptKey)

    /** [key]をインデックスから削除する（Archived/Discarded時）。 */
    fun remove(key: PromptKey)
}
