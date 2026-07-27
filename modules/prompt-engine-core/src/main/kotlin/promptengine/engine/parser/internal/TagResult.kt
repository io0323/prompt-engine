package promptengine.engine.parser.internal

import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.PromptAst

/** `{{ }}` タグ1個を解析した結果（[TagContentParser]参照）。 */
internal sealed interface TagResult {
    data class Node(val node: PromptAst) : TagResult

    data object Skip : TagResult

    data class Terminator(val keyword: String) : TagResult

    data class IfOpen(val condition: Expression) : TagResult

    data class EachOpen(val iterable: Expression, val itemName: String) : TagResult

    data class BlockOpen(val role: BlockRole) : TagResult
}
