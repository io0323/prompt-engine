package promptengine.application.query

import promptengine.domain.dependency.DependencyKind
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.prompt.PromptKey

/**
 * Template/Fragmentの変更による影響範囲（依存Prompt一覧）を返すQueryハンドラ（UC-06、設計書§2.5）。
 *
 * [DependencyRepository.findInboundTemplateOrFragment]は、CompiledPromptの依存が
 * コンパイル時点でPrompt起点に平坦化済みのため（設計書ADR-0033決定3）、
 * 多段のグラフ探索なしに直接・間接の全依存Promptを1回のDBクエリで求められる。
 *
 * [AssetImpactQuery.kind]に[DependencyKind.PROMPT]は指定不可（Prompt→Prompt依存の
 * 影響範囲は既存の[DependenciesHandler]と`direction=in`で取得する）。
 */
data class AssetImpactQuery(val kind: DependencyKind, val assetKey: String)

class GetAssetImpactHandler(
    private val dependencyRepository: DependencyRepository,
) {
    /**
     * [query]で指定したTemplate/Fragmentに依存しているPromptのキー一覧を重複なしで返す。
     * 依存するPromptが存在しない場合は空リストを返す。
     */
    fun handle(query: AssetImpactQuery): List<PromptKey> {
        require(query.kind != DependencyKind.PROMPT) {
            "AssetImpactQuery.kind に PROMPT は指定できません。" +
                "Prompt→Prompt 依存の影響範囲は DependenciesHandler(direction=IN) を使用してください。"
        }
        return dependencyRepository
            .findInboundTemplateOrFragment(query.kind, query.assetKey)
            .map { it.fromKey }
            .distinct()
    }
}
