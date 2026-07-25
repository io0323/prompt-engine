package promptengine.domain.prompt

import promptengine.domain.context.ContextRequirement
import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition

/**
 * Prompt Aggregate内のEntity。1つのSemVerに対応する内容とライフサイクル状態を持つ。
 */
data class PromptVersion(
    val semVer: SemVer,
    val content: PromptContent,
    val variables: List<VariableDefinition> = emptyList(),
    val contextRequirement: ContextRequirement? = null,
    val state: LifecycleState = LifecycleState.Draft,
) {
    /**
     * 内容を差し替えた新しいPromptVersionを返す。
     * Published状態の内容はImmutable（設計書§2.5・§4.3）であるため、
     * 修正が必要な場合は新Versionを作成すること。
     */
    fun withContent(newContent: PromptContent): PromptVersion {
        if (state == LifecycleState.Published) {
            throw InvalidStateTransitionException(state::class.simpleName ?: "Published", "withContent")
        }
        return copy(content = newContent)
    }
}
