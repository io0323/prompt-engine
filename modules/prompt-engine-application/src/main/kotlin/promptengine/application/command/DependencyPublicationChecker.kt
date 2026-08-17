package promptengine.application.command

import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository

/**
 * ガード「依存先が全てPublished」（設計書§2.5）の評価（`PublishHandler`から抽出、
 * `PromoteExperimentHandler`と共有、ADR-0034）。[PublishHandler]のKDoc参照。
 */
internal class DependencyPublicationChecker(
    private val promptRepository: PromptRepository,
    private val templateRepository: TemplateRepository,
    private val fragmentRepository: FragmentRepository,
    private val dependencyRepository: DependencyRepository,
) {
    fun allDependenciesPublished(
        key: PromptKey,
        version: PromptVersion,
    ): Boolean {
        val edges = dependencyRepository.findOutbound(key, version.semVer)
        return edges.all { isPublished(it) }
    }

    private fun isPublished(edge: DependencyEdge): Boolean {
        val range = VersionRange.parse(edge.toVersion)
        return when (edge.toKind) {
            DependencyKind.TEMPLATE ->
                templateRepository.findByKey(TemplateKey(edge.toKey))?.versions
                    ?.any { range.matches(it.semVer) && it.state == PublicationState.Published } ?: false
            DependencyKind.FRAGMENT ->
                fragmentRepository.findByKey(FragmentKey(edge.toKey))?.versions
                    ?.any { range.matches(it.semVer) && it.state == PublicationState.Published } ?: false
            DependencyKind.PROMPT ->
                promptRepository.findByKey(PromptKey(edge.toKey))?.versions
                    ?.any { range.matches(it.semVer) && it.state == LifecycleState.Published } ?: false
        }
    }
}
