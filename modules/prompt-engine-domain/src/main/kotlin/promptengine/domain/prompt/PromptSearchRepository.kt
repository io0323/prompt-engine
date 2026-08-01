package promptengine.domain.prompt

import promptengine.domain.shared.Page

/**
 * `GET /prompts`（設計書§13.1、検索）の条件（ADR-0017）。
 *
 * `page`/`size`は[Page]と同じ範囲（`page >= 0`、`size in 1..MAX_SIZE`）を要求する。
 * 検索リポジトリ実装がSQLのOFFSET/LIMITへ変換する前に、この`init`で不正な値を
 * 拒否する（CodeRabbitレビュー指摘: 検証前にDBへ到達すると負のOFFSET等でエラーになりうる）。
 */
data class PromptSearchCriteria(
    val q: String? = null,
    val tag: String? = null,
    val category: String? = null,
    val status: LifecycleState? = null,
    val page: Int = 0,
    val size: Int = Page.DEFAULT_SIZE,
) {
    init {
        require(page >= 0) { "page must not be negative: $page" }
        require(size in 1..Page.MAX_SIZE) { "size must be between 1 and ${Page.MAX_SIZE}: $size" }
    }
}

/**
 * `GET /prompts`を支えるRead Model（設計書§2.14 Query側、ADR-0017）。`PromptRepository`
 * （Aggregate単位のCommand側）とは独立した読み取り専用の検索インターフェース。
 */
interface PromptSearchRepository {
    /** [criteria]に一致する[PromptSummary]をページングして返す。合致件数0件でも空の[Page]を返す。 */
    fun search(criteria: PromptSearchCriteria): Page<PromptSummary>
}
