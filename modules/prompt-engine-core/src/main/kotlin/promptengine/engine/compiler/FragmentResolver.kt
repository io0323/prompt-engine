package promptengine.engine.compiler

import promptengine.domain.composition.CircularDependencyException
import promptengine.domain.composition.CompositionDepthExceededException
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.CompositionSizeExceededException
import promptengine.domain.composition.DraftReferenceNotAllowedException
import promptengine.domain.composition.FragmentReferenceNotFoundException
import promptengine.domain.composition.IncludeRequiredVariableUnresolvedException
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.fragment.FragmentKey
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.fragment.FragmentVersion
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.PromptAst
import promptengine.engine.parser.PromptDslParser

/**
 * import/includeの解決（設計書§15.4/§15.5、ADR-0009決定4〜7、ADR-0010決定1・2・4・7）。
 * extends（[ReferenceResolver]）とは独立に、Fragment参照のキー参照グラフをDFSで解決し、
 * 変数束縛を適用しながらFragment本体を呼出箇所へ展開する。
 *
 * 各Fragment自身のmacro呼出（`{{ name(...) }}`）は、そのFragment自身が宣言した
 * macroスコープ（設計書§15.6、ADR-0010決定5）で、呼出元へ展開・挿入する前に完全に解決する
 * （呼出元の`{{> frag k=v }}`変数束縛の適用よりも先。呼出側の macro スコープが
 * Fragment内部に漏れ込むことを防ぐ）。したがって呼出元（`CompositionServiceImpl`）が
 * 受け取る本体には、Fragmentが自ら展開したmacroは一切残らない。
 */
class FragmentResolver(
    private val fragmentRepository: FragmentRepository,
    private val maxDepth: Int = ReferenceResolver.DEFAULT_MAX_DEPTH,
    private val maxExpandedSizeBytes: Int = ReferenceResolver.DEFAULT_MAX_EXPANDED_SIZE_BYTES,
) {
    private val parser = PromptDslParser()
    private val macroExpander = MacroExpander()
    private val memo = mutableMapOf<Pair<FragmentKey, SemVer>, MemoizedFragment>()

    data class Result(
        val body: List<PromptAst>,
        val dependencies: List<ResolvedDependency.FragmentDependency>,
        val expandedSizeBytes: Int,
    )

    /** extendsチェーンで既に消費した深さ・サイズを引き継ぐための状態（ADR-0009決定2）。 */
    data class ChainState(val ancestorPath: List<String> = emptyList(), val sizeBytes: Int = 0)

    private data class MemoizedFragment(
        val body: List<PromptAst>,
        val dependency: ResolvedDependency.FragmentDependency,
    )

    private data class ResolvedReference(
        val fragmentKey: FragmentKey,
        val range: VersionRange,
        val fragmentVersion: FragmentVersion,
        val resolvedSemVer: SemVer,
    ) {
        val memoKey: Pair<FragmentKey, SemVer> get() = fragmentKey to resolvedSemVer
        val keyLabel: String get() = "${fragmentKey.value}@$resolvedSemVer"
    }

    internal class ResolutionState(sizeBytes: Int, ancestorPath: List<String>) {
        var expandedSizeBytes: Int = sizeBytes
        val ancestorPath: MutableList<String> = ancestorPath.toMutableList()
        val dependencies = linkedMapOf<Pair<FragmentKey, SemVer>, ResolvedDependency.FragmentDependency>()
    }

    /**
     * [body]中の`{{> }}`（[IncludeNode]、`{{#block}}`/`{{#if}}`/`{{#each}}`内を含め再帰的に）を
     * 展開する。[callerVariableScope]は「呼出側スコープ」（ADR-0010決定1）の初期値
     * （ルートPromptの変数 ∪ extendsチェーンのTemplate変数）。
     */
    fun resolve(
        body: List<PromptAst>,
        imports: List<ImportDeclaration>,
        callerVariableScope: Set<String>,
        mode: CompositionMode,
        chainState: ChainState = ChainState(),
    ): Result {
        val state = ResolutionState(chainState.sizeBytes, chainState.ancestorPath)
        val resolvedBody = resolveNodes(body, imports, callerVariableScope, mode, state)
        return Result(resolvedBody, state.dependencies.values.toList(), state.expandedSizeBytes)
    }

    private fun resolveNodes(
        nodes: List<PromptAst>,
        imports: List<ImportDeclaration>,
        callerVariableScope: Set<String>,
        mode: CompositionMode,
        state: ResolutionState,
    ): List<PromptAst> = nodes.flatMap { resolveNode(it, imports, callerVariableScope, mode, state) }

    private fun resolveNode(
        node: PromptAst,
        imports: List<ImportDeclaration>,
        callerVariableScope: Set<String>,
        mode: CompositionMode,
        state: ResolutionState,
    ): List<PromptAst> =
        when (node) {
            is IncludeNode -> resolveInclude(node, imports, callerVariableScope, mode, state)
            is BlockNode ->
                listOf(BlockNode(node.role, resolveNodes(node.body, imports, callerVariableScope, mode, state)))
            is IfNode ->
                listOf(
                    node.copy(
                        thenBranch = resolveNodes(node.thenBranch, imports, callerVariableScope, mode, state),
                        elseBranch = resolveNodes(node.elseBranch, imports, callerVariableScope, mode, state),
                    ),
                )
            is EachNode -> listOf(node.copy(body = resolveNodes(node.body, imports, callerVariableScope, mode, state)))
            else -> listOf(node)
        }

    private fun resolveInclude(
        node: IncludeNode,
        imports: List<ImportDeclaration>,
        callerVariableScope: Set<String>,
        mode: CompositionMode,
        state: ResolutionState,
    ): List<PromptAst> {
        val target = IncludeTargetResolver.resolve(node.target, node.versionRange, imports)
        val (fragmentVersion, resolvedSemVer) = resolveFragmentVersion(target.fragmentKey, target.range, mode)
        val ref = ResolvedReference(target.fragmentKey, target.range, fragmentVersion, resolvedSemVer)
        state.checkNotCyclic(ref.keyLabel)

        val resolved = memo[ref.memoKey] ?: freshlyResolve(ref, mode, state)
        state.dependencies.putIfAbsent(ref.memoKey, resolved.dependency)

        requireResolvable(fragmentVersion, node.bindings.keys, callerVariableScope, ref.fragmentKey)
        return ExpressionSubstitution.substitute(resolved.body, node.bindings)
    }

    private fun freshlyResolve(
        ref: ResolvedReference,
        mode: CompositionMode,
        state: ResolutionState,
    ): MemoizedFragment {
        state.checkDepth(maxDepth)
        state.ancestorPath += ref.keyLabel
        state.expandedSizeBytes += ref.fragmentVersion.content.source.toByteArray(Charsets.UTF_8).size
        state.checkSize(maxExpandedSizeBytes)

        val dependency =
            ResolvedDependency.FragmentDependency(
                key = ref.fragmentKey,
                requestedRange = ref.range,
                resolvedVersion = ref.resolvedSemVer,
                status = ref.fragmentVersion.state,
                contentHash = ref.fragmentVersion.content.contentHash,
            )
        val document = parser.parse(ref.fragmentVersion.content.source)
        val ownImports = ImportsFieldMapper.parse(document.frontMatter.fields["imports"])
        val ownMacros = MacrosFieldMapper.parse(document.frontMatter.fields["macros"])
        val ownScope = ref.fragmentVersion.variables.map { it.name }.toSet()
        val ownIncludesResolved = resolveNodes(document.body, ownImports, ownScope, mode, state)
        val ownBody = macroExpander.expand(ownIncludesResolved, ownMacros)

        state.ancestorPath.removeAt(state.ancestorPath.size - 1)
        val result = MemoizedFragment(ownBody, dependency)
        memo[ref.memoKey] = result
        return result
    }

    private fun requireResolvable(
        fragmentVersion: FragmentVersion,
        boundNames: Set<String>,
        callerVariableScope: Set<String>,
        fragmentKey: FragmentKey,
    ) {
        val unresolved =
            fragmentVersion.variables.firstOrNull { variable ->
                variable.required && variable.name !in boundNames && variable.name !in callerVariableScope
            }
        if (unresolved != null) throw IncludeRequiredVariableUnresolvedException(fragmentKey, unresolved.name)
    }

    private fun resolveFragmentVersion(
        key: FragmentKey,
        range: VersionRange,
        mode: CompositionMode,
    ): Pair<FragmentVersion, SemVer> {
        val fragment = fragmentRepository.findByKey(key) ?: throw FragmentReferenceNotFoundException(key, range)
        val selected =
            VersionSelector.select(
                versions = fragment.versions,
                info = { VersionSelector.VersionInfo(it.semVer, it.state) },
                query = VersionSelector.Query(range, mode),
                handlers =
                    VersionSelector.FailureHandlers(
                        onNotFound = { failNotFound(key, range) },
                        onDraftNotAllowed = { failDraftNotAllowed(key, range) },
                    ),
            )
        return selected to selected.semVer
    }

    private fun failNotFound(
        key: FragmentKey,
        range: VersionRange,
    ): Nothing = throw FragmentReferenceNotFoundException(key, range)

    private fun failDraftNotAllowed(
        key: FragmentKey,
        range: VersionRange,
    ): Nothing = throw DraftReferenceNotAllowedException("${key.value}@${range.toRangeText() ?: "latest"}")
}

private fun FragmentResolver.ResolutionState.checkDepth(maxDepth: Int) {
    if (ancestorPath.size + 1 > maxDepth) throw CompositionDepthExceededException(maxDepth)
}

private fun FragmentResolver.ResolutionState.checkNotCyclic(keyLabel: String) {
    if (keyLabel in ancestorPath) throw CircularDependencyException(ancestorPath + keyLabel)
}

private fun FragmentResolver.ResolutionState.checkSize(maxExpandedSizeBytes: Int) {
    if (expandedSizeBytes > maxExpandedSizeBytes) {
        throw CompositionSizeExceededException(maxExpandedSizeBytes, expandedSizeBytes)
    }
}
