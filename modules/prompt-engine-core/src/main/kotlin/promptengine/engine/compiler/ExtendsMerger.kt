package promptengine.engine.compiler

import promptengine.domain.composition.DuplicateSuperCallException
import promptengine.domain.composition.SuperWithoutParentBlockException
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.MacroCallNode
import promptengine.domain.template.ast.PromptAst

/**
 * extendsチェーンの `{{#block}}` マージ（設計書§15.3、ADR-0010決定3・6）。
 * `{{ super() }}` は構文上 `MacroCallNode(name = "super", arguments = emptyMap())` として
 * 解析される（P3aのパーサは`super`という名前に意味的な特別扱いを行わない）。本クラスが
 * その解釈（extendsマージ段階でのみ、block本体内に現れた`super`呼出を対象とする）を担う。
 *
 * [ReferenceResolver.resolveExtendsChain]の結果と対になる本文ASTの列（直近の親→…→根本の順）
 * とリーフ自身の本文を受け取り、根本→直近の親→リーフの順にマージした最終本文を返す。
 */
class ExtendsMerger {
    /**
     * [ancestorBodiesNearestFirst] は直近の親→…→根本の順（[ReferenceResolver.resolveExtendsChain]と同じ順序）。
     * [leafBody] は実際にコンパイルするPrompt/Template自身の本文。
     */
    fun merge(
        ancestorBodiesNearestFirst: List<List<PromptAst>>,
        leafBody: List<PromptAst>,
    ): List<PromptAst> {
        var blocksByRole = linkedMapOf<BlockRole, List<PromptAst>>()
        for (body in ancestorBodiesNearestFirst.asReversed()) {
            blocksByRole = mergeLevel(blocksByRole, body)
        }
        val inheritedOnlyRoles = blocksByRole.toList()
        val finalBlocksByRole = mergeLevel(blocksByRole, leafBody)

        return buildOutput(leafBody, inheritedOnlyRoles, finalBlocksByRole)
    }

    private fun mergeLevel(
        parentBlocksByRole: Map<BlockRole, List<PromptAst>>,
        levelBody: List<PromptAst>,
    ): LinkedHashMap<BlockRole, List<PromptAst>> {
        val result = LinkedHashMap(parentBlocksByRole)
        for (node in levelBody) {
            if (node !is BlockNode) continue
            val parentContent = parentBlocksByRole[node.role]
            result[node.role] =
                if (parentContent != null) {
                    substituteSuper(node.body, parentContent, node.role)
                } else {
                    requireNoSuperCall(node.body, node.role)
                    node.body
                }
        }
        return result
    }

    private fun buildOutput(
        leafBody: List<PromptAst>,
        inheritedOnlyRoles: List<Pair<BlockRole, List<PromptAst>>>,
        finalBlocksByRole: Map<BlockRole, List<PromptAst>>,
    ): List<PromptAst> {
        val leafRoles = leafBody.filterIsInstance<BlockNode>().map { it.role }.toSet()
        val output = mutableListOf<PromptAst>()
        for (node in leafBody) {
            output += if (node is BlockNode) BlockNode(node.role, finalBlocksByRole.getValue(node.role)) else node
        }
        for ((role, content) in inheritedOnlyRoles) {
            if (role !in leafRoles) output += BlockNode(role, content)
        }
        return output
    }

    private fun substituteSuper(
        childBody: List<PromptAst>,
        parentContent: List<PromptAst>,
        role: BlockRole,
    ): List<PromptAst> {
        if (countSuperCalls(childBody) > 1) throw DuplicateSuperCallException(role)
        return replaceSuperCalls(childBody, parentContent)
    }

    private fun requireNoSuperCall(
        body: List<PromptAst>,
        role: BlockRole,
    ) {
        if (countSuperCalls(body) > 0) throw SuperWithoutParentBlockException(role)
    }

    private fun countSuperCalls(nodes: List<PromptAst>): Int =
        nodes.sumOf { node ->
            when (node) {
                is MacroCallNode -> if (isSuperCall(node)) 1 else 0
                is IfNode -> countSuperCalls(node.thenBranch) + countSuperCalls(node.elseBranch)
                is EachNode -> countSuperCalls(node.body)
                else -> 0
            }
        }

    private fun replaceSuperCalls(
        nodes: List<PromptAst>,
        parentContent: List<PromptAst>,
    ): List<PromptAst> =
        nodes.flatMap { node ->
            when {
                node is MacroCallNode && isSuperCall(node) -> parentContent
                node is IfNode ->
                    listOf(
                        node.copy(
                            thenBranch = replaceSuperCalls(node.thenBranch, parentContent),
                            elseBranch = replaceSuperCalls(node.elseBranch, parentContent),
                        ),
                    )
                node is EachNode -> listOf(node.copy(body = replaceSuperCalls(node.body, parentContent)))
                else -> listOf(node)
            }
        }

    private fun isSuperCall(node: MacroCallNode): Boolean = node.name == "super" && node.arguments.isEmpty()
}
