package promptengine.domain.composition

import promptengine.domain.context.ContextRequirement
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.variable.VariableDefinition

/**
 * CompositionServiceの解決結果（設計書§4.5・§5.2、ADR-0009）。
 *
 * [body] はextendsマージ・import/include展開・macro展開を終えたASTである
 * （合成規則自体はPR2 `feat/p3c2-composition-rules` のスコープ。本型はPR1で定義する）。
 * [dependencies] は解決済みVersionにピン留めされた依存一覧、[variables] はPrompt自身と
 * 展開されたFragment/Templateが宣言する変数定義のマージ結果、[contextRequirements] は
 * Prompt自身が宣言するContext要求（scope単位で1件）のリスト（ADR-0011。
 * Template/Fragmentのcontext要求とのマージは行わない。TemplateVersion/FragmentVersionは
 * contextRequirementsを持たないため）。
 *
 * data classなので、同一リポジトリ状態から得た2つの`CompiledPrompt`は`==`で
 * 構造的に比較できる（決定性テストの基盤、ADR-0009）。
 */
data class CompiledPrompt(
    val body: List<PromptAst>,
    val dependencies: List<ResolvedDependency>,
    val variables: List<VariableDefinition>,
    val contextRequirements: List<ContextRequirement>,
)
