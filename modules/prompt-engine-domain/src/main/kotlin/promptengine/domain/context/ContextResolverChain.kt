package promptengine.domain.context

import promptengine.domain.shared.PromptRequest

/**
 * Context解決の入口（7スコープをまとめて解決・マージするファサード、設計書§2.6ステージ5・
 * §5.4シーケンス、ADR-0015決定1の原則の追加適用）。
 *
 * `ContextResolverImpl`（`prompt-engine-core`、P4）は当初[ContextResolver]（スコープ単位の
 * Interface）のみを実装対象としており、7スコープをまとめる側のファサード自体はdomain
 * Interfaceを持たない具象クラスだった。[VariableResolverChain][promptengine.domain.variable.VariableResolverChain]
 * が既にこの形（Chainのファサードをdomainに置く）を持つのに対し不整合であり、
 * Pipeline Orchestrator（P8、`prompt-engine-application`）がStage 5（Resolve Context）で
 * `prompt-engine-core`に依存せず`ContextResolverImpl`へ委譲できるようにするため、
 * ADR-0015が確立した原則（上位レイヤの依存性逆転のみを理由にdomain Interfaceを置くことも
 * 正当）を適用してここに新設する。
 */
interface ContextResolverChain {
    /**
     * `required`のpathが1件でも解決できなければ[ContextUnavailableException]を投げる。
     * 実装契約の詳細は`ContextResolverImpl`のKDocを参照。
     */
    fun resolve(
        requirements: List<ContextRequirement>,
        request: PromptRequest,
    ): ContextBindingSet
}
