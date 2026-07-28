package promptengine.domain.template

import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition

/**
 * Template Aggregate内のEntity。1つのSemVerに対応する内容とライフサイクル状態を持つ（設計書§4.3）。
 *
 * プライマリコンストラクタは `internal`（[ConsistentCopyVisibility] により `copy()` も
 * 同様に `internal`）。任意の `state` を外部から直接指定して構築できてしまうと、
 * State パターンの遷移を経ずに不正な状態のインスタンスが作れてしまうため
 * （[promptengine.domain.prompt.PromptVersion] と同じ理由、ADR-0006）。
 * 通常の新規作成は [Template.create] / [Template.newVersion]（常にDraft）を、
 * 永続化層からの復元は [Template.restore] を使うこと。
 *
 * [extendsKey] はextends先の [TemplateKey] のみを保持する。Version範囲（`@^2`等）の
 * 解釈・解決は3c（CompositionService）のスコープであり、本Aggregateは範囲文字列を
 * 保持しない（ADR-0008）。
 */
@ConsistentCopyVisibility
data class TemplateVersion internal constructor(
    val semVer: SemVer,
    val content: TemplateContent,
    val variables: List<VariableDefinition> = emptyList(),
    val extendsKey: TemplateKey? = null,
    val state: PublicationState = PublicationState.Draft,
)
