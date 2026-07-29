package promptengine.domain.prompt

import promptengine.domain.context.ContextRequirement
import promptengine.domain.shared.SemVer
import promptengine.domain.template.ExtendsRef
import promptengine.domain.variable.VariableDefinition

/**
 * Prompt Aggregate内のEntity。1つのSemVerに対応する内容とライフサイクル状態を持つ。
 *
 * プライマリコンストラクタは `internal`（[ConsistentCopyVisibility] により `copy()` も
 * 同様に `internal`）。任意の `state` を外部から直接指定して構築できてしまうと、
 * State パターンの遷移を経ずに不正な状態のインスタンスが作れてしまうため。
 * 通常の新規作成は [Prompt.create] / [Prompt.newVersion]（常にDraft）を、
 * 永続化層からの復元は [Prompt.restore] を使うこと（ADR-0006）。
 *
 * [extends] はPromptがextendsするTemplateの参照（key + Version範囲）を保持する
 * （設計書§15.1/§15.3、ADR-0009）。PromptはTemplateしかextendsできないため、
 * 参照先は常に [promptengine.domain.template.TemplateKey]。
 *
 * [contextRequirements] はPromptが宣言する各Contextスコープの要求（scope単位で1件）の
 * リストである（設計書§6クラス図、ADR-0011。P1〜P3c時点は単数の実装漏れがあった）。
 */
@ConsistentCopyVisibility
data class PromptVersion internal constructor(
    val semVer: SemVer,
    val content: PromptContent,
    val variables: List<VariableDefinition> = emptyList(),
    val contextRequirements: List<ContextRequirement> = emptyList(),
    val extends: ExtendsRef? = null,
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
