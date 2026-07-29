package promptengine.engine.compiler

import promptengine.domain.composition.CircularDependencyException
import promptengine.domain.composition.CompositionDepthExceededException
import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.CompositionSizeExceededException
import promptengine.domain.composition.DraftReferenceNotAllowedException
import promptengine.domain.composition.ResolvedDependency
import promptengine.domain.composition.TemplateReferenceNotFoundException
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.VersionRange
import promptengine.domain.template.ExtendsRef
import promptengine.domain.template.TemplateKey
import promptengine.domain.template.TemplateRepository
import promptengine.domain.template.TemplateVersion

/**
 * extendsチェーン（Prompt/Template → Template、設計書§15.3、ADR-0009）のキー参照グラフを
 * DFSで解決する（設計書§4.5「CompositionService | ...循環検出（DFS）...」）。
 *
 * extendsは単一継承（§15.3「単一継承のみ」）であり、`TemplateVersion.extends`が
 * `null`になるまで辿ると必ず1本の鎖になる（Fragment includeのような分岐・再合流は
 * 起きない）。そのため訪問済み判定は「解決チェーン中に同じTemplateKeyが2回現れたか」
 * だけで十分であり、Fragment include解決（PR2、`feat/p3c2-composition-rules`）で
 * 必要になる「祖先パスか、正当な別分岐からの再訪か」というメモ化の区別はここでは不要。
 *
 * `import`/`include`（Fragment）の解決はPR2のスコープ（Fragment本文のパースが必要な
 * ため）。本クラスが提供する[selectVersion]相当のSemVer範囲解決・Status検証ロジックは
 * PR2からも再利用される想定。
 */
class ReferenceResolver(
    private val templateRepository: TemplateRepository,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxExpandedSizeBytes: Int = DEFAULT_MAX_EXPANDED_SIZE_BYTES,
) {
    /**
     * [rootDescription]（呼出元、例: `"prompt:support/faq-answer"`）が[rootExtends]から
     * 辿るextendsチェーンを解決する。[rootExtends]が`null`なら空リストを返す。
     */
    fun resolveExtendsChain(
        rootDescription: String,
        rootExtends: ExtendsRef?,
        mode: CompositionMode,
    ): List<ResolvedDependency.TemplateDependency> {
        val result = mutableListOf<ResolvedDependency.TemplateDependency>()
        val visitedKeys = mutableListOf(rootDescription)
        var accumulatedSizeBytes = 0
        var current = rootExtends

        while (current != null) {
            checkDepth(visitedKeys)

            val (version, resolvedSemVer) = resolveTemplateVersion(current.key, current.range, mode)

            val keyLabel = current.key.value
            checkNotCyclic(visitedKeys, keyLabel)
            visitedKeys += keyLabel

            accumulatedSizeBytes += version.content.source.toByteArray(Charsets.UTF_8).size
            checkSize(accumulatedSizeBytes)

            result +=
                ResolvedDependency.TemplateDependency(
                    key = current.key,
                    requestedRange = current.range,
                    resolvedVersion = resolvedSemVer,
                    status = version.state,
                    contentHash = version.content.contentHash,
                )

            current = version.extends
        }

        return result
    }

    private fun checkDepth(visitedKeys: List<String>) {
        if (visitedKeys.size > maxDepth) throw CompositionDepthExceededException(maxDepth)
    }

    private fun checkNotCyclic(
        visitedKeys: List<String>,
        keyLabel: String,
    ) {
        if (keyLabel in visitedKeys) throw CircularDependencyException(visitedKeys + keyLabel)
    }

    private fun checkSize(accumulatedSizeBytes: Int) {
        if (accumulatedSizeBytes > maxExpandedSizeBytes) {
            throw CompositionSizeExceededException(maxExpandedSizeBytes, accumulatedSizeBytes)
        }
    }

    /**
     * [key]のTemplateから、[range]にマッチし、かつ[mode]が許すStatusの中で最大のVersionを選ぶ。
     * 同じTemplateRepository状態に対しては常に同じ結果を返す純粋な選択（決定性、ADR-0009）。
     * 選択ロジック自体は[VersionSelector]に委譲する（Fragment解決とも共有、ADR-0010）。
     */
    private fun resolveTemplateVersion(
        key: TemplateKey,
        range: VersionRange,
        mode: CompositionMode,
    ): Pair<TemplateVersion, SemVer> {
        val template = templateRepository.findByKey(key) ?: throw TemplateReferenceNotFoundException(key, range)
        val selected =
            VersionSelector.select(
                versions = template.versions,
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
        key: TemplateKey,
        range: VersionRange,
    ): Nothing = throw TemplateReferenceNotFoundException(key, range)

    private fun failDraftNotAllowed(
        key: TemplateKey,
        range: VersionRange,
    ): Nothing = throw DraftReferenceNotAllowedException("${key.value}@${range.toRangeText() ?: "latest"}")

    companion object {
        /** 設計書§15.5の深さ上限（extends/import/include/macro解決チェーンの通算、ADR-0009決定2）。 */
        const val DEFAULT_MAX_DEPTH = 5

        /** 設計書§15.5の展開後サイズ上限の既定値（1MB）。 */
        const val DEFAULT_MAX_EXPANDED_SIZE_BYTES = 1_000_000
    }
}
