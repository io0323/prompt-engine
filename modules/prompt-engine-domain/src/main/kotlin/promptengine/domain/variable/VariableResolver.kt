package promptengine.domain.variable

import promptengine.domain.shared.PromptRequest

/**
 * Variable Resolver Chainを構成する1つのResolver（設計書§3.4・§16-2、拡張ポイント#2）。
 *
 * Interfaceはdomainに置き、実装（6種の標準Resolver）は`prompt-engine-core`が持つ
 * （[CompositionService][promptengine.domain.composition.CompositionService]と同じ
 * 「domainにInterface、実装は下流モジュール」の形。ADR-0011決定4）。
 *
 * [resolve] は解決できなければ`null`を返す（設計書§3.4疑似コードの`Optional<Value>`に対応）。
 * Explicit Parameter以外の5種は、`definition.source`が自分の担当種別と一致しない変数には
 * 常に`null`を返す（ADR-0011決定1。宣言と異なる経路からの偶然の解決を構造的に防ぐ）。
 * Explicit Parameterを解決するResolverだけは`source`を見ない
 * （設計書§2.8「明示パラメータ最優先」）。
 *
 * PluginManager自体の実装は後続フェーズだが、この interface を介して差し替え可能な
 * 構造になっている（設計書§16「拡張ポイント」）。
 */
interface VariableResolver {
    fun resolve(
        definition: VariableDefinition,
        request: PromptRequest,
    ): Any?
}
