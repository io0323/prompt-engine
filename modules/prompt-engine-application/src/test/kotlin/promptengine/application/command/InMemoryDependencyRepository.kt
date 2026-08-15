package promptengine.application.command

import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

class InMemoryDependencyRepository : DependencyRepository {
    private val outbound = mutableMapOf<Pair<PromptKey, SemVer>, List<DependencyEdge>>()

    override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

    override fun findOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
    ): List<DependencyEdge> = outbound[promptKey to semVer] ?: emptyList()

    override fun findInbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

    override fun findInboundTemplateOrFragment(
        kind: DependencyKind,
        key: String,
    ): List<DependencyEdge> = outbound.values.flatten().filter { it.toKind == kind && it.toKey == key }

    override fun replaceOutbound(
        promptKey: PromptKey,
        semVer: SemVer,
        edges: List<DependencyEdge>,
    ) {
        outbound[promptKey to semVer] = edges
    }
}
