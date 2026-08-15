package promptengine.application.command

import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyKind
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer

/**
 * [CompositionService.compile][promptengine.domain.composition.CompositionService.compile]
 * （COMPILE_ONLY）が返す[CompiledPrompt.dependencies][promptengine.domain.composition.CompiledPrompt.dependencies]
 * から`dependencies`テーブル（設計書§12）へ書き込む[DependencyEdge]を組み立てる（ADR-0033決定3）。
 *
 * `CompiledPrompt.dependencies`はextendsチェーン全体（祖先Templateを含む）とFragment
 * include連鎖（Fragment内Fragmentを含む）をコンパイル時点で1階層（Prompt起点）に平坦化済みの
 * フルセットである（[promptengine.engine.compiler.ReferenceResolver]・
 * [promptengine.engine.compiler.FragmentResolver]参照）。これを唯一の書き込み経路とすることで、
 * 「実際にcompileが使う依存」と「`dependencies`テーブルに書かれる依存」が構造的に一致する
 * （P9b時点で存在した、import/include(FRAGMENT)由来の依存が一切書き込まれない欠落の修正）。
 */
internal fun dependencyEdgesFrom(
    promptKey: PromptKey,
    semVer: SemVer,
    dependencies: List<ResolvedDependency>,
): List<DependencyEdge> =
    dependencies.map { dependency ->
        val (toKind, toKey) =
            when (dependency) {
                is ResolvedDependency.TemplateDependency -> DependencyKind.TEMPLATE to dependency.key.value
                is ResolvedDependency.FragmentDependency -> DependencyKind.FRAGMENT to dependency.key.value
            }
        DependencyEdge(
            fromKey = promptKey,
            fromVersion = semVer,
            toKind = toKind,
            toKey = toKey,
            toVersion = dependency.requestedRange.toRangeText(),
        )
    }
