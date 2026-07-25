package promptengine.domain.prompt

import promptengine.domain.context.ContextRequirement
import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition

/**
 * [Prompt.create] / [Prompt.newVersion] に渡す、新規Version作成に必要な素材。
 *
 * [PromptVersion] とは異なり `state` を持たない。新規に作られるVersionは
 * §2.5 の「(新規)→Draft」の通り常にDraft状態であるべきで、これを呼び出し側が
 * 任意の状態（Published等）で構築して渡せてしまうと、Aggregateが保証すべき
 * 状態遷移の不変条件が型の外側から迂回できてしまう。`state` フィールド自体を
 * 持たないことで、それを型で不可能にする。
 */
data class NewPromptVersion(
    val semVer: SemVer,
    val content: PromptContent,
    val variables: List<VariableDefinition> = emptyList(),
    val contextRequirement: ContextRequirement? = null,
)
