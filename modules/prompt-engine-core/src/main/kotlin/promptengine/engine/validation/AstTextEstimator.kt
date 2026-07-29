package promptengine.engine.validation

import promptengine.domain.context.ContextBindingSet
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.ExpressionOperand
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.NumberLiteral
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode
import promptengine.domain.variable.BindingSet

/**
 * `LengthValidationRule`向けの、Render前段階でのASTベストエフォート文字数推定
 * （ADR-0012決定5。実際の`RenderEngine`/`TemplateEngine`（P6）は使わない）。
 *
 * - [TextNode]: そのまま連結
 * - [ExprNode]: `PropertyRef`の先頭セグメントが`"context"`なら[ContextBindingSet]、
 *   それ以外は[BindingSet]から値を引き、見つかれば`toString()`を連結する
 *   （見つからなければ0文字）。`truncate(n)`フィルタがあれば結果をその長さで切り詰める
 *   （他のフィルタは文字数に影響しないため無視する）。
 * - [IfNode]: then/else双方を推定し、長い方を採用する（予算超過の見逃しより
 *   過大見積りを許容する安全側）。
 * - [EachNode]: `iterable`が実際に`List<*>`として解決できればその要素数ぶん
 *   `body`を繰り返す。解決できなければ1回分として扱う。ループ変数
 *   （`itemName`）自体の値は推定できない（このEstimatorは評価器を持たないため）。
 * - [BlockNode]: `role`を問わず`body`をそのまま連結する。
 * - [IncludeNode]/[MacroCallNode]: Composition（P3c）で必ず展開済みのはずで
 *   Validation段階のASTには本来出現しない。防御的に空文字列を返す。
 */
object AstTextEstimator {
    fun estimate(
        nodes: List<PromptAst>,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): String = nodes.joinToString(separator = "") { estimateNode(it, variableBindings, contextBindings) }

    private fun estimateNode(
        node: PromptAst,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): String =
        when (node) {
            is TextNode -> node.text
            is ExprNode -> estimateExpression(node.expression, variableBindings, contextBindings)
            is IfNode -> estimateIf(node, variableBindings, contextBindings)
            is EachNode -> estimateEach(node, variableBindings, contextBindings)
            is BlockNode -> estimate(node.body, variableBindings, contextBindings)
            is IncludeNode -> ""
            is MacroCallNode -> ""
        }

    private fun estimateIf(
        node: IfNode,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): String {
        val thenText = estimate(node.thenBranch, variableBindings, contextBindings)
        val elseText = estimate(node.elseBranch, variableBindings, contextBindings)
        return if (thenText.length >= elseText.length) thenText else elseText
    }

    private fun estimateEach(
        node: EachNode,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): String {
        val bodyText = estimate(node.body, variableBindings, contextBindings)
        val iterableValue = resolvePropertyRefValue(node.iterable.operand, variableBindings, contextBindings)
        val count = (iterableValue as? List<*>)?.size ?: 1
        return bodyText.repeat(count)
    }

    private fun estimateExpression(
        expression: Expression,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): String {
        val raw = resolvePropertyRefValue(expression.operand, variableBindings, contextBindings) ?: return ""
        var text = raw.toString()
        val truncateArg = expression.filters.firstOrNull { it.name == TRUNCATE_FILTER }?.arguments?.firstOrNull()
        if (truncateArg is NumberLiteral) {
            val limit = truncateArg.value.toInt()
            if (limit in 0 until text.length) text = text.substring(0, limit)
        }
        return text
    }

    private fun resolvePropertyRefValue(
        operand: ExpressionOperand,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): Any? {
        val ref = operand as? PropertyRef ?: return null
        return if (ref.path.first() == CONTEXT_SEGMENT) {
            if (ref.path.size < MIN_CONTEXT_PATH_SIZE) {
                null
            } else {
                contextBindings.values[ref.path.drop(1).joinToString(separator = ".")]
            }
        } else {
            variableBindings[ref.path.first()]
        }
    }

    private const val TRUNCATE_FILTER = "truncate"
    private const val CONTEXT_SEGMENT = "context"
    private const val MIN_CONTEXT_PATH_SIZE = 3
}
