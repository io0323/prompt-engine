package promptengine.engine.resolver

import promptengine.domain.context.ContextBindingSet
import promptengine.domain.context.ContextRequirement
import promptengine.domain.context.ContextResolver
import promptengine.domain.context.ContextResolverChain
import promptengine.domain.context.ContextUnavailableException
import promptengine.domain.shared.PromptRequest

/**
 * [ContextResolver]の7スコープをまとめて解決・マージする（設計書§3.3・§5.4シーケンス）。
 * [ContextResolverChain]の実装（ADR-0015決定1の原則）。
 *
 * マージ順序は設計書§2.7の通り固定（後勝ち）:
 * `environment → system → application → workflow → user → memory → conversation`。
 *
 * [requirements]に含まれない（＝Promptが宣言していない）スコープは一切参照・注入しない
 * （設計書§2.7「未宣言スコープへの参照はValidationエラー」。Validation Engine自体はP5の
 * スコープのため、ここではスコープを注入しないだけに留める）。
 *
 * `required`のpathが1件でも解決できなければ、[ContextUnavailableException]を投げる。
 * [VariableResolverChain.resolveAll]と同様、最初の1件で止めずスコープ全体を処理してから
 * 未解決分をまとめて例外に含める。`optional`のpathが解決できない場合は例外にせず
 * [ContextBindingSet.warnings]に積んで処理を続ける（設計書§5.4）。
 */
class ContextResolverImpl(
    private val resolvers: List<ContextResolver>,
) : ContextResolverChain {
    private val resolversByScope: Map<String, ContextResolver> = resolvers.associateBy { it.scope() }

    /**
     * [requirements]（`PromptVersion.contextRequirements`と同じscope単位で1件の宣言リスト、
     * 重複scopeはドメイン層で構築時に拒否済み）を解決・マージし[ContextBindingSet]を返す。
     * `required`未充足時は[ContextUnavailableException]を投げる（クラスのKDoc参照）。
     */
    override fun resolve(
        requirements: List<ContextRequirement>,
        request: PromptRequest,
    ): ContextBindingSet {
        val requirementsByScope = requirements.associateBy { it.scope }
        val values = linkedMapOf<String, Any>()
        val warnings = mutableListOf<String>()
        val missingRequired = mutableListOf<String>()

        for (scope in MERGE_ORDER) {
            val requirement = requirementsByScope[scope] ?: continue
            val available = resolversByScope[scope]?.resolve(requirement, request) ?: emptyMap()

            for (path in requirement.required) {
                val value = available[path]
                if (value != null) values["$scope.$path"] = value else missingRequired += "$scope.$path"
            }
            for (path in requirement.optional) {
                val value = available[path]
                if (value != null) {
                    values["$scope.$path"] = value
                } else {
                    warnings += "optional context not resolved: $scope.$path"
                }
            }
        }

        if (missingRequired.isNotEmpty()) {
            throw ContextUnavailableException(missingRequired)
        }
        return ContextBindingSet(values, warnings)
    }

    companion object {
        /** 設計書§2.7のマージ順序（後勝ち）。 */
        val MERGE_ORDER = listOf("environment", "system", "application", "workflow", "user", "memory", "conversation")

        /** 設計書§16-3「7 scope標準」の既定のResolver構成。 */
        fun standard(): ContextResolverImpl = ContextResolverImpl(MERGE_ORDER.map { StandardContextResolver(it) })
    }
}
