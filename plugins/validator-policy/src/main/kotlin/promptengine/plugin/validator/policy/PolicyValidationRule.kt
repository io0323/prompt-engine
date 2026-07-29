package promptengine.plugin.validator.policy

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.TextNode
import promptengine.domain.validation.Finding
import promptengine.domain.validation.Severity
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.BindingSet

/**
 * 禁止語・PII混入・組織ポリシー準拠（設計書§2.10）。このリポジトリで最初のPlugin実装
 * （`promptengine.plugin.validator.policy`、ADR-0003）。
 *
 * [bannedWords]は設定注入（ハードコードしない、CLAUDE.md「やってはいけないこと」）。
 * [id]がDSLの`validation.policies`（設計書§15.7）に含まれる場合のみ検証を行い、
 * 含まれなければ空リストを返す（ADR-0012決定4と同じ「前提が無ければ黙って該当なし」の
 * 扱い。Prompt側が明示的にこのPolicyを要求していない限り常時適用しない）。
 * 走査対象は[TextNode]の文字列のみ（束縛値やプレースホルダの中身までは検査しない、
 * 著者が直接書いたテキストのポリシー準拠を見るという趣旨）。
 */
class PolicyValidationRule(
    private val bannedWords: List<String>,
    private val ruleId: String = DEFAULT_RULE_ID,
    private val ruleSeverity: Severity = Severity.ERROR,
) : ValidationRule {
    override fun id(): String = ruleId

    override fun severity(): Severity = ruleSeverity

    override fun validate(
        compiled: CompiledPrompt,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<Finding> {
        if (id() !in compiled.validation.policies) return emptyList()

        val text = collectLiteralText(compiled.body)
        return bannedWords
            .filter { text.contains(it, ignoreCase = true) }
            .map { word ->
                Finding(
                    ruleId = id(),
                    path = "$.body",
                    severity = severity(),
                    message = "banned word detected: '$word'",
                )
            }
    }

    private fun collectLiteralText(nodes: List<PromptAst>): String = nodes.joinToString(separator = "") { visit(it) }

    private fun visit(node: PromptAst): String =
        when (node) {
            is TextNode -> node.text
            is ExprNode -> ""
            is IfNode -> collectLiteralText(node.thenBranch) + collectLiteralText(node.elseBranch)
            is EachNode -> collectLiteralText(node.body)
            is BlockNode -> collectLiteralText(node.body)
            is IncludeNode -> ""
            is MacroCallNode -> ""
        }

    companion object {
        const val DEFAULT_RULE_ID = "no-pii"
    }
}
