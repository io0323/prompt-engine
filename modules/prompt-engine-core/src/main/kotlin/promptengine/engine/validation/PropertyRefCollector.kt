package promptengine.engine.validation

import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode

/**
 * ASTを再帰的に走査し、式（条件・プレースホルダ・反復対象・フィルタ引数）に現れる全ての
 * `PropertyRef`を収集する（`PlaceholderValidationRule`向け）。[IncludeNode]/[MacroCallNode]は
 * Composition（P3c）で必ず展開済みのはずでValidation段階のASTには本来出現しないため、
 * 走査対象に含めない。
 */
object PropertyRefCollector {
    fun collect(nodes: List<PromptAst>): List<PropertyRef> {
        val refs = mutableListOf<PropertyRef>()
        nodes.forEach { visit(it, refs) }
        return refs
    }

    private fun visit(
        node: PromptAst,
        refs: MutableList<PropertyRef>,
    ) {
        when (node) {
            is TextNode -> Unit
            is ExprNode -> visitExpression(node.expression, refs)
            is IfNode -> {
                visitExpression(node.condition, refs)
                node.thenBranch.forEach { visit(it, refs) }
                node.elseBranch.forEach { visit(it, refs) }
            }
            is EachNode -> {
                visitExpression(node.iterable, refs)
                node.body.forEach { visit(it, refs) }
            }
            is BlockNode -> node.body.forEach { visit(it, refs) }
            is IncludeNode -> Unit
            is MacroCallNode -> Unit
        }
    }

    private fun visitExpression(
        expression: Expression,
        refs: MutableList<PropertyRef>,
    ) {
        (expression.operand as? PropertyRef)?.let { refs += it }
        expression.filters.forEach { filter ->
            filter.arguments.forEach { argument -> (argument as? PropertyRef)?.let { refs += it } }
        }
    }
}
