package promptengine.domain.prompt

import promptengine.domain.context.ContextRequirement
import promptengine.domain.shared.SemVer
import promptengine.domain.template.ExtendsRef
import promptengine.domain.variable.VariableDefinition

/**
 * 永続化層（DBの行）が保持する [PromptVersion] の材料一式（`state` を含む）。
 * [PromptVersion] のプライマリコンストラクタが `internal` であるため、
 * `prompt-engine-infrastructure` はこのVOを経由してのみ任意状態のVersionを
 * [Prompt.restore] に渡せる（ADR-0006）。GoF Mementoパターンに倣った命名で、
 * Event Storeの「スナップショット」（`prompt_snapshots` テーブル、設計書§12）とは
 * 意図的に呼び分けている。
 */
data class PromptVersionMemento(
    val semVer: SemVer,
    val content: PromptContent,
    val variables: List<VariableDefinition>,
    val contextRequirements: List<ContextRequirement>,
    val extends: ExtendsRef? = null,
    val state: LifecycleState,
)
