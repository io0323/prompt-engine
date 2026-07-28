package promptengine.domain.template

import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition

/**
 * [Template.create] / [Template.newVersion] に渡す、新規Version作成に必要な素材。
 *
 * [TemplateVersion] とは異なり `state` を持たない。新規に作られるVersionは常に
 * Draft状態であるべきで、これを呼び出し側が任意の状態（Published等）で構築して
 * 渡せてしまうと、Aggregateが保証すべき状態遷移の不変条件が型の外側から
 * 迂回できてしまう（[promptengine.domain.prompt.NewPromptVersion] と同じ理由）。
 */
data class NewTemplateVersion(
    val semVer: SemVer,
    val content: TemplateContent,
    val variables: List<VariableDefinition> = emptyList(),
    val extends: ExtendsRef? = null,
)
