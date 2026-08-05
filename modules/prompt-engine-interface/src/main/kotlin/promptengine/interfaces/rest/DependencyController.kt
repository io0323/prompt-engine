package promptengine.interfaces.rest

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import promptengine.application.query.DependenciesHandler
import promptengine.application.view.DomainValueFactory
import promptengine.application.view.QueryFactory
import promptengine.application.view.handleView
import promptengine.interfaces.dto.DependencyEdgeDto

/** `GET /prompts/{namespace}/{name}/dependencies?direction=in|out`（設計書§13.1、[PromptController]のKDoc参照）。 */
@RestController
@RequestMapping("/api/v1/prompts/{namespace}/{name}/dependencies")
class DependencyController(private val dependenciesHandler: DependenciesHandler) {
    @GetMapping
    @PreAuthorize("hasAuthority('prompt:read')")
    fun list(
        @PathVariable namespace: String,
        @PathVariable name: String,
        @RequestParam direction: String,
    ): List<DependencyEdgeDto> {
        val key = DomainValueFactory.promptKeyText(namespace, name)
        return dependenciesHandler.handleView(QueryFactory.dependenciesQuery(key, direction)).map {
            DependencyEdgeDto(it.fromKey, it.fromVersion, it.toKind, it.toKey, it.toVersion)
        }
    }
}
