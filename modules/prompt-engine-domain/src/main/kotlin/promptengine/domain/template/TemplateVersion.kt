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
 * [extends] はextends先の [TemplateKey] とVersion範囲（`@^2`等）の両方を保持する
 * （ADR-0009。当初ADR-0008はkeyのみの保持としていたが、CompositionServiceが範囲を
 * 解決するには構造化フィールドだけでは情報が足りないことが判明し改訂した）。
 */
@ConsistentCopyVisibility
data class TemplateVersion internal constructor(
    val semVer: SemVer,
    val content: TemplateContent,
    val variables: List<VariableDefinition> = emptyList(),
    val extends: ExtendsRef? = null,
    val state: PublicationState = PublicationState.Draft,
)
