package promptengine.application.command

import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import promptengine.domain.template.ExtendsRef

/**
 * [extends]から`dependencies`テーブル（設計書§12）へ書き込むための[DependencyEdge]を組み立てる。
 *
 * P9b時点ではextends由来のTEMPLATE依存のみを書き込む。import/include（FRAGMENT）・
 * Nested Prompt（PROMPT、Issue #19未実装）由来の依存は対象外（docs/prompts/p9b.md参照）。
 */
internal fun dependencyEdgesFrom(
    promptKey: PromptKey,
    semVer: SemVer,
    extends: ExtendsRef?,
): List<DependencyEdge> =
    listOfNotNull(
        extends?.let {
            DependencyEdge(
                fromKey = promptKey,
                fromVersion = semVer,
                toKind = DependencyKind.TEMPLATE,
                toKey = it.key.value,
                toVersion = it.range.toRangeText(),
            )
        },
    )
