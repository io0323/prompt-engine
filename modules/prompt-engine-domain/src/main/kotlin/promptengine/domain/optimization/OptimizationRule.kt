package promptengine.domain.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.shared.TokenCount

/**
 * Optimizationを構成する1つのRule（Strategy、設計書§2.11・§3.4・§5.6）。
 *
 * §3.4疑似コードの`applicable(ast: ExpandedAst, profile: ModelProfile)`/
 * `optimize(ast: ExpandedAst, profile: ModelProfile): (ExpandedAst, OptimizationNote)`は、
 * [promptengine.domain.validation.ValidationRule]と同様に実在しない`ExpandedAst`を
 * 実際の[CompiledPrompt]へ読み替えたもの（ADR-0013決定9）。
 *
 * `Compression`/`ContextOptimization`（設計書§2.11）は会話履歴・Contextスコープの
 * データそのもの（[ContextBindingSet]）を書き換える必要があるため、[compiled]に加え
 * [contextBindings]も引数・戻り値に持つ（ADR-0013決定9訂正）。呼出パラメータ
 * （`variableBindings`）はいずれのRuleの対象にも含まれないため引数に含めない
 * （見積り算出専用に使う[promptengine.domain.optimization.OptimizationEngine]側のみが
 * 参照する）。
 *
 * [estimatedTokens]・[budget]は`OptimizationEngine`が算出・保持する現在の見積り値であり、
 * `Compression`の適用条件（§2.11「tokenEstimate > budget」）判定にのみ使う。他のRuleは
 * 無視してよい（見積りロジックを各Rule実装に重複させないため、Rule自身が再計算はしない）。
 *
 * [ValidationRule][promptengine.domain.validation.ValidationRule]と異なり、Optimizationは
 * 意味保存が原則であり、常に登録順で全Ruleを一様に呼ぶ単純なloop適用とする
 * （Chain of Responsibilityではない）。
 */
interface OptimizationRule {
    /** このRuleを一意に識別するID（[OptimizationNote.ruleId]に対応）。 */
    fun id(): String

    /** 現在の状態からこのRuleを適用すべきかどうかを返す。 */
    fun applicable(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): Boolean

    /**
     * [compiled]・[contextBindings]を最適化し、最適化後の状態と適用内容の記録を返す。
     * [estimatedTokens]・[budget]は`Compression`が「どこまで切り詰めれば`budget`以内に
     * 収まるか」を判断するために使う（他のRuleは無視してよい）。
     */
    fun optimize(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): RuleOptimizationResult
}
