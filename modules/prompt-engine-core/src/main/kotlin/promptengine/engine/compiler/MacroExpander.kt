package promptengine.engine.compiler

import promptengine.domain.composition.MacroNotFoundException
import promptengine.domain.composition.MacroRecursionException
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PromptAst

/**
 * macroのローカル展開（設計書§15.6、ADR-0010決定5）。[macros]は展開対象の本文が属する
 * 宣言単位（Prompt/Template/Fragment）自身が`macros:`で定義したものに限る（呼出元の
 * macroは見えない）。
 *
 * `{{ super() }}`（引数無し）はextendsマージ段階でのみ解決される（[ExtendsMerger]、
 * ADR-0010決定3）。[passThroughBareSuperCalls]が`true`の場合、引数無し`super`呼出は
 * 展開を試みず素通しする（`CompositionServiceImpl`がextendsマージより前に各階層の本文へ
 * 適用する1回目のmacro展開で使う。マージ後、ブロックの外に残っていた`super`呼出だけを
 * 最終段（`passThroughBareSuperCalls = false`）が通常の未定義macro呼出として検出する）。
 */
class MacroExpander(private val passThroughBareSuperCalls: Boolean = false) {
    fun expand(
        body: List<PromptAst>,
        macros: List<MacroDeclaration>,
    ): List<PromptAst> {
        val byName = macros.associateBy { it.name }
        return expandNodes(body, byName, callStack = emptyList())
    }

    private fun expandNodes(
        nodes: List<PromptAst>,
        macros: Map<String, MacroDeclaration>,
        callStack: List<String>,
    ): List<PromptAst> = nodes.flatMap { expandNode(it, macros, callStack) }

    private fun expandNode(
        node: PromptAst,
        macros: Map<String, MacroDeclaration>,
        callStack: List<String>,
    ): List<PromptAst> =
        when (node) {
            is MacroCallNode -> expandMacroCall(node, macros, callStack)
            is BlockNode -> listOf(BlockNode(node.role, expandNodes(node.body, macros, callStack)))
            is IfNode ->
                listOf(
                    node.copy(
                        thenBranch = expandNodes(node.thenBranch, macros, callStack),
                        elseBranch = expandNodes(node.elseBranch, macros, callStack),
                    ),
                )
            is EachNode -> listOf(node.copy(body = expandNodes(node.body, macros, callStack)))
            else -> listOf(node)
        }

    private fun expandMacroCall(
        node: MacroCallNode,
        macros: Map<String, MacroDeclaration>,
        callStack: List<String>,
    ): List<PromptAst> {
        if (passThroughBareSuperCalls && isSuperCall(node)) return listOf(node)

        val declaration = macros[node.name] ?: throw MacroNotFoundException(node.name)
        if (node.name in callStack) throw MacroRecursionException(node.name)

        val substituted = ExpressionSubstitution.substitute(declaration.body, node.arguments)
        return expandNodes(substituted, macros, callStack + node.name)
    }
}
