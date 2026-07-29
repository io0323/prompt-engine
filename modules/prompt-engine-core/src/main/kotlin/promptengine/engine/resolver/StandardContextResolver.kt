package promptengine.engine.resolver

import promptengine.domain.context.ContextRequirement

/**
 * 標準のContextResolver実装（設計書§16-3「7 scope標準」）。7スコープいずれも
 * [PromptRequest.contextData]（呼出元・AACP・PE環境設定等が供給した生データ）から
 * 該当スコープの値を引くだけの同一ロジックであるため、スコープ名だけをパラメータ化した
 * 1クラスで賄う（差替が必要なスコープだけ、別の[ContextResolver]実装に個別入替可能）。
 */
class StandardContextResolver(private val scope: String) : ContextResolver {
    override fun scope(): String = scope

    override fun resolve(
        requirement: ContextRequirement,
        request: PromptRequest,
    ): Map<String, Any> = request.contextData[scope] ?: emptyMap()
}
