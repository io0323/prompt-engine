package promptengine.domain.variable

import promptengine.domain.shared.PromptRequest

/**
 * Variable解決の入口（Chain of Responsibilityのファサード、設計書§3.3）。
 *
 * `prompt-engine-application`（Pipeline Orchestrator、P8）はこのInterfaceだけを参照し、
 * `prompt-engine-core`の実装（`VariableResolverChainImpl`、6種標準Resolverの実際の連鎖）には
 * 依存しない（[CompositionService][promptengine.domain.composition.CompositionService]と
 * 同じ「domainにInterface、実装は下流モジュール」の形。ADR-0011決定4）。
 *
 * [resolveAll] は`required`の変数が1件でも解決できなければ
 * [VariableUnresolvedException]を投げる（設計書§5.3「未解決の変数名を1つ目で止めずに
 * 全て列挙して返すこと」）。
 */
interface VariableResolverChain {
    fun resolveAll(
        definitions: List<VariableDefinition>,
        request: PromptRequest,
    ): BindingSet
}
