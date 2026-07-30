package promptengine.engine.render

import promptengine.domain.render.MessageRole
import promptengine.domain.render.RenderedMessage
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.TextNode

/**
 * [PromptAst]の本文を[RenderedMessage]列へ変換する（ADR-0013決定4「BlockNodeからmessagesへの
 * 変換規則」）。
 *
 * トップレベル（ネストの深さを問わず、`IfNode`/`EachNode`内も含む）を順に走査し、
 * [BlockNode]に出会うたびにそれ自身を1つのmessageとして確定させる。同一roleの[BlockNode]が
 * 複数あっても出現順のまま個別messageとして保持し、マージしない。[BlockNode]の間・前後に
 * 存在する「role指定の無い」テキストは暗黙の`USER` messageとしてバッファへ蓄積し、
 * 次の[BlockNode]の直前・走査終了時にflushする（ASTに[BlockNode]が1つも無い場合は
 * この暗黙バッファのみが最後にflushされ、単一の`USER` messageになる）。
 */
internal class MessageWalker(private val rootScope: Scope) {
    private val messages = mutableListOf<RenderedMessage>()
    private val buffer = StringBuilder()

    fun walk(nodes: List<PromptAst>): List<RenderedMessage> {
        visitAll(nodes, rootScope)
        if (messages.isEmpty()) {
            messages += RenderedMessage(MessageRole.USER, buffer.toString())
        } else if (buffer.isNotEmpty()) {
            flush()
        }
        return messages
    }

    private fun visitAll(
        nodes: List<PromptAst>,
        scope: Scope,
    ) = nodes.forEach { visit(it, scope) }

    private fun visit(
        node: PromptAst,
        scope: Scope,
    ) {
        when (node) {
            is TextNode -> buffer.append(node.text)
            is ExprNode -> buffer.append(ExpressionEvaluator.evaluate(node.expression, scope))
            is IfNode -> visitAll(chooseBranch(node, scope), scope)
            is EachNode -> visitEach(node, scope)
            is BlockNode -> {
                flush()
                messages += RenderedMessage(node.role.toMessageRole(), evaluateText(node.body, scope))
            }
            is IncludeNode, is MacroCallNode -> Unit
        }
    }

    private fun visitEach(
        node: EachNode,
        scope: Scope,
    ) {
        val list = resolveIterableList(node, scope)
        if (list != null) {
            list.forEach { item -> visitAll(node.body, scope.withLocal(node.itemName, item ?: Unit)) }
        } else {
            visitAll(node.body, scope)
        }
    }

    private fun flush() {
        if (buffer.isNotEmpty()) {
            messages += RenderedMessage(MessageRole.USER, buffer.toString())
            buffer.clear()
        }
    }

    private fun BlockRole.toMessageRole(): MessageRole =
        when (this) {
            BlockRole.SYSTEM -> MessageRole.SYSTEM
            BlockRole.USER -> MessageRole.USER
            BlockRole.ASSISTANT -> MessageRole.ASSISTANT
        }
}
