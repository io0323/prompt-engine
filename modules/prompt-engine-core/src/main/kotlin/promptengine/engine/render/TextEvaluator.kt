package promptengine.engine.render

import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.TextNode

/**
 * ASTノード列を1つの文字列へ評価する（[BlockNode]の本文・`IfNode`の選択枝・`EachNode`の
 * 反復本文向け）。
 *
 * 入れ子の[BlockNode]（DSL文法§15.1上は出現しない想定）は防御的にテキストとして
 * 平坦化する（役割は失われるが、クラッシュや値の消失は起きない）。[IncludeNode]/
 * [MacroCallNode]はComposition（P3c）で必ず展開済みのはずでRender段階のASTには
 * 本来出現しないため、防御的に空文字列として扱う（[promptengine.engine.validation.AstTextEstimator]
 * と同じ方針）。
 */
internal fun evaluateText(
    nodes: List<PromptAst>,
    scope: Scope,
): String {
    val sb = StringBuilder()
    nodes.forEach { node ->
        when (node) {
            is TextNode -> sb.append(node.text)
            is ExprNode -> sb.append(ExpressionEvaluator.evaluate(node.expression, scope))
            is IfNode -> sb.append(evaluateText(chooseBranch(node, scope), scope))
            is EachNode -> sb.append(evaluateEachAsText(node, scope))
            is BlockNode -> sb.append(evaluateText(node.body, scope))
            is IncludeNode, is MacroCallNode -> Unit
        }
    }
    return sb.toString()
}

internal fun chooseBranch(
    node: IfNode,
    scope: Scope,
): List<PromptAst> {
    val conditionValue = ExpressionEvaluator.resolveOperand(node.condition.operand, scope)
    return if (ExpressionEvaluator.isTruthy(conditionValue)) node.thenBranch else node.elseBranch
}

internal fun resolveIterableList(
    node: EachNode,
    scope: Scope,
): List<*>? = ExpressionEvaluator.resolveOperand(node.iterable.operand, scope) as? List<*>

private fun evaluateEachAsText(
    node: EachNode,
    scope: Scope,
): String {
    val list = resolveIterableList(node, scope)
    return if (list != null) {
        list.joinToString(
            separator = "",
        ) { item -> evaluateText(node.body, scope.withLocal(node.itemName, item ?: Unit)) }
    } else {
        // Compile-onlyや型不一致で反復対象が解決できない場合は1回分として扱う
        // （AstTextEstimatorの「1回分として扱う」という既存の安全側の扱いと整合させる、ADR-0013決定10）。
        evaluateText(node.body, scope)
    }
}
