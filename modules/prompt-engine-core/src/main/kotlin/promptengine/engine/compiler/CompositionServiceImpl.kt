package promptengine.engine.compiler

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.CompositionService
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.template.TemplateRepository
import promptengine.domain.template.TemplateVersion
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.variable.VariableDefinition
import promptengine.engine.parser.PromptDslParser

/**
 * [CompositionService]の実装（`prompt-engine-core`、ADR-0010決定9）。設計書§15.3の解決順
 * （extends → import → include → macro展開）で1つの[PromptVersion]を[CompiledPrompt]へ合成する。
 *
 * extendsチェーンの各階層（Prompt自身と各祖先Template）は、それぞれ独立に「自身のimport/include
 * 解決 → 自身のmacro展開」を完了させたうえで、[ExtendsMerger]によるblockマージへ渡す
 * （macroスコープが宣言単位に閉じる、ADR-0010決定5、の帰結）。深さ・サイズ上限
 * （ADR-0009決定2「通算」）は、extendsチェーンの消費量を[FragmentResolver.ChainState]の
 * 初期値として引き継ぎ、各階層のInclude解決を通じて累積する。
 *
 * 各階層のmacro展開（1回目）は、引数無し`{{ super() }}` を素通しする
 * [MacroExpander]（`passThroughBareSuperCalls = true`）で行う（super()の解決は
 * extendsマージでのみ行うため、ADR-0010決定3）。マージ後、`{{#block}}`の外に残った
 * （＝マージが解決できなかった）`super`呼出だけを、通常のmacro展開（2回目、リーフ自身の
 * macroスコープ）で「未定義macro」として検出する。
 */
class CompositionServiceImpl(
    private val templateRepository: TemplateRepository,
    private val fragmentRepository: FragmentRepository,
    private val maxDepth: Int = ReferenceResolver.DEFAULT_MAX_DEPTH,
    private val maxExpandedSizeBytes: Int = ReferenceResolver.DEFAULT_MAX_EXPANDED_SIZE_BYTES,
) : CompositionService {
    private val parser = PromptDslParser()
    private val referenceResolver = ReferenceResolver(templateRepository, maxDepth, maxExpandedSizeBytes)
    private val extendsMerger = ExtendsMerger()
    private val preMergeMacroExpander = MacroExpander(passThroughBareSuperCalls = true)
    private val finalMacroExpander = MacroExpander()

    private class UnitCompilationContext(
        val staticScope: Set<String>,
        val mode: CompositionMode,
        val depthSeed: List<String>,
        val fragmentResolver: FragmentResolver,
    )

    private data class UnitResolution(
        val body: List<PromptAst>,
        val dependencies: List<ResolvedDependency.FragmentDependency>,
        val sizeBytes: Int,
    )

    override fun compile(
        promptKey: PromptKey,
        promptVersion: PromptVersion,
        mode: CompositionMode,
    ): CompiledPrompt {
        val extendsChain =
            referenceResolver.resolveExtendsChain("prompt:${promptKey.value}", promptVersion.extends, mode)
        val ancestorVersions = extendsChain.map { fetchTemplateVersion(it) }

        val context =
            UnitCompilationContext(
                staticScope = computeStaticScope(promptVersion, ancestorVersions),
                mode = mode,
                depthSeed = List(extendsChain.size) { i -> "extends-hop-$i" },
                fragmentResolver = FragmentResolver(fragmentRepository, maxDepth, maxExpandedSizeBytes),
            )
        val extendsBytes = ancestorVersions.sumOf { it.content.source.toByteArray(Charsets.UTF_8).size }

        val fragmentDependencies = mutableListOf<ResolvedDependency.FragmentDependency>()
        var sizeSoFar = extendsBytes
        val ancestorBodies =
            ancestorVersions.map { templateVersion ->
                val resolution = resolveUnit(templateVersion.content.source, sizeSoFar, context)
                sizeSoFar = resolution.sizeBytes
                fragmentDependencies += resolution.dependencies
                resolution.body
            }
        val leafResolution = resolveUnit(promptVersion.content.source, sizeSoFar, context)
        fragmentDependencies += leafResolution.dependencies

        val mergedBody = extendsMerger.merge(ancestorBodies, leafResolution.body)
        val leafFrontMatter = parser.parse(promptVersion.content.source).frontMatter
        val leafMacros = MacrosFieldMapper.parse(leafFrontMatter.fields["macros"])
        val finalBody = finalMacroExpander.expand(mergedBody, leafMacros)
        val dedupedFragmentDeps = fragmentDependencies.distinctBy { it.key to it.resolvedVersion }
        val dependencies: List<ResolvedDependency> = extendsChain + dedupedFragmentDeps

        return CompiledPrompt(
            body = finalBody,
            dependencies = dependencies,
            variables = resolveVariables(promptVersion, ancestorVersions, dedupedFragmentDeps),
            contextRequirements = promptVersion.contextRequirements,
            validation = promptVersion.validation,
            output = promptVersion.output,
        )
    }

    private fun resolveUnit(
        source: String,
        sizeSoFar: Int,
        context: UnitCompilationContext,
    ): UnitResolution {
        val document = parser.parse(source)
        val imports = ImportsFieldMapper.parse(document.frontMatter.fields["imports"])
        val macros = MacrosFieldMapper.parse(document.frontMatter.fields["macros"])
        val chainState = FragmentResolver.ChainState(context.depthSeed, sizeSoFar)
        val includeResult =
            context.fragmentResolver.resolve(document.body, imports, context.staticScope, context.mode, chainState)
        val macroExpanded = preMergeMacroExpander.expand(includeResult.body, macros)
        return UnitResolution(macroExpanded, includeResult.dependencies, includeResult.expandedSizeBytes)
    }

    private fun computeStaticScope(
        promptVersion: PromptVersion,
        ancestorVersions: List<TemplateVersion>,
    ): Set<String> {
        val ancestorNames = ancestorVersions.flatMap { v -> v.variables.map { it.name } }
        return (promptVersion.variables.map { it.name } + ancestorNames).toSet()
    }

    private fun resolveVariables(
        promptVersion: PromptVersion,
        ancestorVersions: List<TemplateVersion>,
        fragmentDependencies: List<ResolvedDependency.FragmentDependency>,
    ): List<VariableDefinition> {
        val seen = linkedMapOf<String, VariableDefinition>()
        val sources =
            listOf(promptVersion.variables) +
                ancestorVersions.map { it.variables } +
                fragmentDependencies.map { fetchFragmentVariables(it) }
        for (variables in sources) for (variable in variables) seen.putIfAbsent(variable.name, variable)
        return seen.values.toList()
    }

    private fun fetchTemplateVersion(dependency: ResolvedDependency.TemplateDependency): TemplateVersion {
        val template =
            requireNotNull(templateRepository.findByKey(dependency.key)) {
                "resolved TemplateKey must exist: ${dependency.key.value}"
            }
        return template.versions.first { it.semVer == dependency.resolvedVersion }
    }

    private fun fetchFragmentVariables(dependency: ResolvedDependency.FragmentDependency): List<VariableDefinition> {
        val fragment =
            requireNotNull(fragmentRepository.findByKey(dependency.key)) {
                "resolved FragmentKey must exist: ${dependency.key.value}"
            }
        return fragment.versions.first { it.semVer == dependency.resolvedVersion }.variables
    }
}
