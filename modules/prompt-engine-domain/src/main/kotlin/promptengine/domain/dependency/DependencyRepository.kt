package promptengine.domain.dependency

import promptengine.domain.prompt.PromptKey

/**
 * `GET /prompts/{key}/dependencies?direction=in|out`（設計書§13.1）を支えるRepository（ADR-0017）。
 */
interface DependencyRepository {
    /** [promptKey]のPublished Version（無ければ最新Version）が直接参照するTemplate/Fragment/Promptの一覧（direction=out）。 */
    fun findOutbound(promptKey: PromptKey): List<DependencyEdge>

    /** [promptKey]（任意Version）をToとして参照している他Promptの依存関係の一覧（direction=in）。 */
    fun findInbound(promptKey: PromptKey): List<DependencyEdge>
}
