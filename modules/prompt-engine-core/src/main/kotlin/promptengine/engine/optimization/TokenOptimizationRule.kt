package promptengine.engine.optimization

import promptengine.domain.composition.CompiledPrompt
import promptengine.domain.context.ContextBindingSet
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.optimization.OptimizationNote
import promptengine.domain.optimization.OptimizationRule
import promptengine.domain.optimization.RuleOptimizationResult
import promptengine.domain.shared.TokenCount
import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.TextNode
import promptengine.domain.tokenizer.TokenizerPlugin

/**
 * 冗長表現の正規化（設計書§2.11「空白・重複指示の削減」）。常時適用可能（[enabled]で無効化可）。
 *
 * - 空白: 行末の空白/タブを除去し、連続する空白/タブを1個へ、3行以上連続する空行を
 *   2行へ圧縮する（[TextNode]のみが対象。式・プレースホルダの値は対象外）。
 * - 重複指示: 正規化後、隣接する兄弟ノードが同一内容の[TextNode]であれば後者を除去する
 *   （ブロック境界を跨いだ重複は対象外。著者が意図せず同じ指示を2回書いた場合の想定）。
 *
 * [tokensSaved][OptimizationNote.tokensSaved]は正規化前後のリテラルテキスト
 * （[TextNode]の連結のみ、束縛値は含まない）の見積り差分。
 */
class TokenOptimizationRule(
    private val tokenizerPlugin: TokenizerPlugin,
    private val enabled: Boolean = true,
) : OptimizationRule {
    override fun id(): String = RULE_ID

    override fun applicable(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): Boolean = enabled

    override fun optimize(
        compiled: CompiledPrompt,
        contextBindings: ContextBindingSet,
        profile: ModelProfile,
        estimatedTokens: TokenCount,
        budget: TokenCount,
    ): RuleOptimizationResult {
        val before = literalText(compiled.body)
        val normalizedBody = normalize(compiled.body, trimTrailingAtEnd = true)
        val after = literalText(normalizedBody)
        val tokensSaved =
            (tokenizerPlugin.estimate(before).value - tokenizerPlugin.estimate(after).value).coerceAtLeast(0)

        return RuleOptimizationResult(
            compiled = compiled.copy(body = normalizedBody),
            contextBindings = contextBindings,
            note =
                OptimizationNote(
                    id(),
                    TokenCount(tokensSaved),
                    "collapsed redundant whitespace and adjacent duplicate text",
                ),
        )
    }

    private fun literalText(nodes: List<PromptAst>): String =
        nodes.joinToString(separator = "") { node ->
            when (node) {
                is TextNode -> node.text
                is IfNode -> literalText(node.thenBranch) + literalText(node.elseBranch)
                is EachNode -> literalText(node.body)
                is BlockNode -> literalText(node.body)
                else -> ""
            }
        }

    /**
     * [trimTrailingAtEnd]は、この[nodes]列内の最後のノードのテキスト絶対末尾の空白を
     * 除去してよいかどうか。列の途中にあるノードの絶対末尾は必ず`false`で扱う
     * （隣接する兄弟ノードとの間の区切り空白を保持するため。CodeRabbitレビュー指摘:
     * `TextNode("hello ")` + `TextNode("world")`のような並びで各ノードを独立に
     * 絶対末尾トリムすると`"helloworld"`のように単語が連結してしまう）。
     */
    private fun normalize(
        nodes: List<PromptAst>,
        trimTrailingAtEnd: Boolean,
    ): List<PromptAst> {
        val mapped =
            nodes.mapIndexed { index, node ->
                normalizeNode(node, trimTrailingAtEnd = trimTrailingAtEnd && index == nodes.lastIndex)
            }
        return dedupeAdjacentText(mapped)
    }

    private fun normalizeNode(
        node: PromptAst,
        trimTrailingAtEnd: Boolean,
    ): PromptAst =
        when (node) {
            is TextNode -> TextNode(normalizeText(node.text, trimTrailingAtEnd))
            is IfNode ->
                node.copy(
                    thenBranch = normalize(node.thenBranch, trimTrailingAtEnd),
                    elseBranch = normalize(node.elseBranch, trimTrailingAtEnd),
                )
            is EachNode ->
                // 反復本文は複数回描画されうるため、絶対末尾トリムを伝播しない（伝播すると
                // 全ての反復回でトリムされ、反復間の区切り空白まで失われてしまう）。
                node.copy(body = normalize(node.body, trimTrailingAtEnd = false))
            is BlockNode -> node.copy(body = normalize(node.body, trimTrailingAtEnd))
            else -> node
        }

    private fun normalizeText(
        text: String,
        trimTrailingAtEnd: Boolean,
    ): String {
        val withoutInternalTrailingWhitespace = text.replace(TRAILING_WHITESPACE_BEFORE_NEWLINE, "\n")
        val absoluteEndTrimmed =
            if (trimTrailingAtEnd) {
                withoutInternalTrailingWhitespace.replace(TRAILING_WHITESPACE_AT_STRING_END, "")
            } else {
                withoutInternalTrailingWhitespace
            }
        return absoluteEndTrimmed
            .replace(HORIZONTAL_WHITESPACE_RUN, " ")
            .replace(EXCESS_BLANK_LINES, "\n\n")
    }

    private fun dedupeAdjacentText(nodes: List<PromptAst>): List<PromptAst> {
        val result = mutableListOf<PromptAst>()
        for (node in nodes) {
            val last = result.lastOrNull()
            if (node is TextNode && last is TextNode && last.text == node.text) continue
            result += node
        }
        return result
    }

    private companion object {
        const val RULE_ID = "TokenOptimization"

        /** 行内の各改行の直前の空白/タブ（常に安全、隣接ノードとの境界には影響しない）。 */
        val TRAILING_WHITESPACE_BEFORE_NEWLINE = Regex("[ \t]+\n")

        /**
         * テキストの絶対末尾（"\n"を伴わない終端）の空白/タブ。[normalize]が
         * 列の最後のノードにのみ適用する（途中のノードに適用すると隣接ノードとの
         * 区切り空白を失ってしまうため）。
         */
        val TRAILING_WHITESPACE_AT_STRING_END = Regex("""[ \t]+$""")
        val HORIZONTAL_WHITESPACE_RUN = Regex("[ \t]+")
        val EXCESS_BLANK_LINES = Regex("\n{3,}")
    }
}
