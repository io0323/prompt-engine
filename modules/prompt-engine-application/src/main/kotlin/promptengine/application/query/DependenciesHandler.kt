package promptengine.application.query

import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.prompt.PromptKey

/** `GET /prompts/{key}/dependencies?direction=in|out`（設計書§13.1）。 */
enum class DependencyDirection { IN, OUT }

data class DependenciesQuery(val key: PromptKey, val direction: DependencyDirection)

class DependenciesHandler(
    private val dependencyRepository: DependencyRepository,
) {
    fun handle(query: DependenciesQuery): List<DependencyEdge> =
        when (query.direction) {
            DependencyDirection.OUT -> dependencyRepository.findOutbound(query.key)
            DependencyDirection.IN -> dependencyRepository.findInbound(query.key)
        }
}
