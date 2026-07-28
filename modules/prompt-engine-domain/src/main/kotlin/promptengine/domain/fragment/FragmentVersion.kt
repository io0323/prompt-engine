package promptengine.domain.fragment

import promptengine.domain.shared.PublicationState
import promptengine.domain.shared.SemVer
import promptengine.domain.variable.VariableDefinition

/**
 * Fragment Aggregate内のEntity。1つのSemVerに対応する内容とライフサイクル状態を持つ（設計書§4.3）。
 *
 * プライマリコンストラクタは `internal`（[ConsistentCopyVisibility] により `copy()` も
 * 同様に `internal`）。通常の新規作成は [Fragment.create] / [Fragment.newVersion]
 * （常にDraft）を、永続化層からの復元は [Fragment.restore] を使うこと。
 *
 * Templateと異なり構造化されたInclude先フィールドを持たない。`{{> }}` によるInclude参照は
 * DSL本文（[content]）の中に埋め込まれたテキストであり、それを解釈するのは
 * `prompt-engine-core` のParser/CompositionServiceの責務である。`domain`モジュールは
 * 他のいかなるモジュールにも依存できないため、Fragment Aggregateは自己Includeを含め、
 * 循環検出を一切行わない（ADR-0008）。
 */
@ConsistentCopyVisibility
data class FragmentVersion internal constructor(
    val semVer: SemVer,
    val content: FragmentContent,
    val variables: List<VariableDefinition> = emptyList(),
    val state: PublicationState = PublicationState.Draft,
)
