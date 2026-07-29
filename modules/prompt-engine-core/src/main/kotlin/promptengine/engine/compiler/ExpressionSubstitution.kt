package promptengine.engine.compiler

import promptengine.domain.composition.InvalidVariableSubstitutionException
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.Literal
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.TextNode

/**
 * Include束縛（`{{> frag k=v }}`）・macro引数（`{{ name(k=v) }}`）で共通の変数置換機構
 * （設計書§15.5/§15.6、ADR-0010決定2）。ASTを再帰的に走査し、`PropertyRef`の先頭セグメントが
 * [bindings]のキーと一致する式を、束縛Expressionで構造的に置換する。
 */
object ExpressionSubstitution {
    fun substitute(
        nodes: List<PromptAst>,
        bindings: Map<String, Expression>,
    ): List<PromptAst> = nodes.map { substituteNode(it, bindings) }

    private fun substituteNode(
        node: PromptAst,
        bindings: Map<String, Expression>,
    ): PromptAst =
        when (node) {
            is TextNode -> node
            is ExprNode -> ExprNode(substituteExpression(node.expression, bindings))
            is IfNode ->
                IfNode(
                    substituteExpression(node.condition, bindings),
                    substitute(node.thenBranch, bindings),
                    substitute(node.elseBranch, bindings),
                )
            is EachNode ->
                EachNode(
                    substituteExpression(node.iterable, bindings),
                    node.itemName,
                    // ループ変数名はFragment/macro本体内でシャドーイングする（外側の束縛を隠す）。
                    substitute(node.body, bindings - node.itemName),
                )
            is BlockNode -> BlockNode(node.role, substitute(node.body, bindings))
            is IncludeNode -> substituteInclude(node, bindings)
            is MacroCallNode ->
                MacroCallNode(node.name, node.arguments.mapValues { substituteExpression(it.value, bindings) })
        }

    private fun substituteInclude(
        node: IncludeNode,
        bindings: Map<String, Expression>,
    ): IncludeNode {
        val substitutedBindings = node.bindings.mapValues { substituteExpression(it.value, bindings) }
        return IncludeNode(node.target, node.versionRange, substitutedBindings)
    }

    /**
     * 束縛Expressionのoperandが[PropertyRef]なら先頭セグメントだけを差し替えて残りのパスを
     * 保持する。[Literal]で、かつ置換対象の式がさらにドット参照しようとしている場合は
     * [InvalidVariableSubstitutionException]を投げる。フィルタは束縛側→元側の順に連結する。
     */
    private fun substituteExpression(
        expression: Expression,
        bindings: Map<String, Expression>,
    ): Expression {
        val operand = expression.operand
        val boundName = (operand as? PropertyRef)?.path?.first()
        val bound = boundName?.let { bindings[it] }
        return if (operand !is PropertyRef || bound == null) {
            expression
        } else {
            val newOperand =
                when (val boundOperand = bound.operand) {
                    is PropertyRef -> PropertyRef(boundOperand.path + operand.path.drop(1))
                    is Literal -> {
                        if (operand.path.size > 1) throw InvalidVariableSubstitutionException(boundName)
                        boundOperand
                    }
                }
            Expression(newOperand, bound.filters + expression.filters)
        }
    }
}
