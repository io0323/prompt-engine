package promptengine.domain.prompt

import promptengine.domain.shared.Page

/**
 * `GET /prompts`（設計書§13.1、検索）の条件（ADR-0017）。
 */
data class PromptSearchCriteria(
    val q: String? = null,
    val tag: String? = null,
    val category: String? = null,
    val status: LifecycleState? = null,
    val page: Int = 0,
    val size: Int = Page.DEFAULT_SIZE,
)

/**
 * `GET /prompts`を支えるRead Model（設計書§2.14 Query側、ADR-0017）。`PromptRepository`
 * （Aggregate単位のCommand側）とは独立した読み取り専用の検索インターフェース。
 */
interface PromptSearchRepository {
    fun search(criteria: PromptSearchCriteria): Page<PromptSummary>
}
