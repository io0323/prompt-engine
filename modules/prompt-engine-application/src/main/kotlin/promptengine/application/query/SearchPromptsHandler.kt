package promptengine.application.query

import promptengine.domain.prompt.PromptSearchCriteria
import promptengine.domain.prompt.PromptSearchRepository
import promptengine.domain.prompt.PromptSummary
import promptengine.domain.shared.Page

/** `GET /prompts`（設計書§13.1、検索）。トランザクション境界なし（§2.2 CQRS）。 */
data class SearchPromptsQuery(val criteria: PromptSearchCriteria)

class SearchPromptsHandler(
    private val promptSearchRepository: PromptSearchRepository,
) {
    fun handle(query: SearchPromptsQuery): Page<PromptSummary> = promptSearchRepository.search(query.criteria)
}
