package promptengine.domain.validation

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.variable.BindingSet

/**
 * Validation Rule Chainを構成する1つのRule（設計書§3.4・§16-4、拡張ポイント#4）。
 *
 * Interfaceはdomainに置き、実装（標準5種）は`prompt-engine-core`、`PolicyValidationRule`は
 * `plugins/validator-policy`が持つ（[promptengine.domain.composition.CompositionService]と
 * 同じ「domainにInterface、実装は下流モジュール」の形、ADR-0012決定1）。
 *
 * §3.4疑似コードの`validate(ast: ExpandedAst, bindings: BindingSet)`は、実在しない
 * `ExpandedAst`を実際の[CompiledPrompt]（P3c Composition解決済みAST）に、単一の
 * `bindings`をVariable/Context双方の束縛に対応させたもの（ADR-0012決定1）。
 *
 * [severity] はこのRuleが通常報告する既定のseverity。実際に[validate]が返す各
 * [Finding]は自分自身の`severity`を持ち、必ずしも[severity]の値と一致しない
 * （例: `PlaceholderValidationRule`はDSL宣言に応じてFindingごとに計算する、ADR-0012決定3）。
 *
 * 各Ruleは自身の検証に必要な入力（[variableBindings]の該当キー等）が無い/空でも
 * 例外を投げず、検証対象が無いものとして空リストを返す（ADR-0012決定4。
 * Compile-onlyモードでは[variableBindings]/[contextBindings]が実質空になりうるため）。
 */
interface ValidationRule {
    fun id(): String

    fun severity(): Severity

    fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<Finding>
}
