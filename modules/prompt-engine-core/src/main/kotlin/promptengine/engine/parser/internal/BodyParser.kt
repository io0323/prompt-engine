package promptengine.engine.parser.internal

import promptengine.domain.template.ast.BlockNode
import promptengine.domain.template.ast.EachNode
import promptengine.domain.template.ast.IfNode
import promptengine.domain.template.ast.ParseError
import promptengine.domain.template.ast.ParseErrorKind
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.template.ast.PromptDslParseException
import promptengine.domain.template.ast.TextNode

/**
 * Prompt DSL本文（設計書§15.1）の再帰下降パーサ。`{{ }}` タグの位置を追跡しつつ
 * [PromptAst] の木を構築する。構文エラーは最初の1件で停止する（P3実装ガイドの方針）。
 * タグ内容（式・include・macro呼出）の解析自体は[TagContentParser]に委譲し、
 * その中で見つかったエラーはタグ全体の開始位置（`{{` の位置）に丸めて報告する。
 */
internal class BodyParser(
    bodyText: String,
    bodyStartLine: Int,
    private val sourceLines: List<String>,
    private val maxNestingDepth: Int,
) {
    private val cursor = Cursor(bodyText, bodyStartLine)

    /**
     * トップレベル（`terminators`が空集合）で[parseUntil]が呼び出し元に終端タグを
     * 返すことは無い。空集合と非照合の終端タグは[handleTag]がその場で`fail`するため。
     * よって`.first`のみで安全（監査: 分岐カバレッジ）。
     */
    fun parse(): List<PromptAst> = parseUntil(depth = 0, terminators = emptySet()).first

    private fun parseUntil(
        depth: Int,
        terminators: Set<String>,
    ): Pair<List<PromptAst>, String?> {
        val nodes = mutableListOf<PromptAst>()
        val textBuffer = StringBuilder()
        var result: Pair<List<PromptAst>, String?>? = null

        while (result == null) {
            if (cursor.atEnd()) {
                flushText(textBuffer, nodes)
                result = nodes to null
            } else if (!cursor.startsWith("{{")) {
                textBuffer.append(cursor.advance())
            } else {
                val tagStart = cursor.position()
                val tag = parseTag(tagStart)
                if (tag !is TagResult.Skip) {
                    flushText(textBuffer, nodes)
                    result = handleTag(tag, depth, terminators, tagStart, nodes)
                }
            }
        }
        return result
    }

    /** タグを1件処理する。戻り値がnullなら継続、非nullならparseUntilの終了（終端タグ到達）を意味する。 */
    private fun handleTag(
        tag: TagResult,
        depth: Int,
        terminators: Set<String>,
        tagStart: Position,
        nodes: MutableList<PromptAst>,
    ): Pair<List<PromptAst>, String?>? {
        when (tag) {
            is TagResult.Node -> nodes.add(tag.node)
            is TagResult.Terminator -> {
                if (tag.keyword !in terminators) {
                    fail(tagStart, "unexpected '{{${tag.keyword}}}' without a matching opening tag")
                }
                return nodes to tag.keyword
            }
            is TagResult.IfOpen -> nodes.add(buildIfNode(tag, depth, tagStart))
            is TagResult.EachOpen -> nodes.add(buildEachNode(tag, depth, tagStart))
            is TagResult.BlockOpen -> nodes.add(buildBlockNode(tag, depth, tagStart))
            is TagResult.Skip -> Unit
        }
        return null
    }

    private fun buildIfNode(
        tag: TagResult.IfOpen,
        depth: Int,
        tagStart: Position,
    ): IfNode {
        checkDepth(depth, tagStart)
        val (thenBranch, t1) = parseUntil(depth + 1, setOf("else", "/if"))
        if (t1 == null) fail(tagStart, "unterminated '{{#if}}': missing '{{/if}}'")
        val elseBranch =
            if (t1 == "else") {
                val (branch, t2) = parseUntil(depth + 1, setOf("/if"))
                if (t2 == null) fail(tagStart, "unterminated '{{#if}}': missing '{{/if}}'")
                branch
            } else {
                emptyList()
            }
        return IfNode(tag.condition, thenBranch, elseBranch)
    }

    private fun buildEachNode(
        tag: TagResult.EachOpen,
        depth: Int,
        tagStart: Position,
    ): EachNode {
        checkDepth(depth, tagStart)
        val (body, terminator) = parseUntil(depth + 1, setOf("/each"))
        if (terminator == null) fail(tagStart, "unterminated '{{#each}}': missing '{{/each}}'")
        return EachNode(tag.iterable, tag.itemName, body)
    }

    private fun buildBlockNode(
        tag: TagResult.BlockOpen,
        depth: Int,
        tagStart: Position,
    ): BlockNode {
        checkDepth(depth, tagStart)
        val (body, terminator) = parseUntil(depth + 1, setOf("/block"))
        if (terminator == null) fail(tagStart, "unterminated '{{#block}}': missing '{{/block}}'")
        return BlockNode(tag.role, body)
    }

    private fun flushText(
        textBuffer: StringBuilder,
        nodes: MutableList<PromptAst>,
    ) {
        if (textBuffer.isNotEmpty()) {
            nodes.add(TextNode(textBuffer.toString()))
            textBuffer.clear()
        }
    }

    /**
     * `{{#if}}/{{#each}}/{{#block}}` の入れ子数の上限を検査する。
     * ここでの深さはDSL本文内の構文ネストのみを指し、Composition解決
     * （extends/import/include、設計書§15.5）の深さ上限5とは別概念（3aのスコープ外、3cで扱う）。
     * 混同しないこと。
     */
    private fun checkDepth(
        currentDepth: Int,
        tagStart: Position,
    ) {
        if (currentDepth + 1 > maxNestingDepth) {
            fail(
                tagStart,
                "nesting depth exceeded maximum of $maxNestingDepth",
                kind = ParseErrorKind.NESTING_TOO_DEEP,
            )
        }
    }

    private fun parseTag(tagStart: Position): TagResult {
        cursor.advance()
        cursor.advance()
        val content = readTagContent(cursor) { message -> fail(tagStart, message) }
        return TagContentParser { message -> fail(tagStart, message) }.parse(content.trim())
    }

    /**
     * `position.line`はcursorが本文中の改行数から積み上げた値であり、本文は
     * `sourceLines`の末尾側の部分列そのものなので、`sourceLines`の範囲を
     * 超えることはない（監査時点で確認済み、`getOrElse`のフォールバックは削除済み）。
     */
    private fun fail(
        position: Position,
        message: String,
        kind: ParseErrorKind = ParseErrorKind.SYNTAX_ERROR,
    ): Nothing {
        val lineText = sourceLines[position.line - 1]
        throw PromptDslParseException(ParseError(kind, position.line, position.column, lineText, message))
    }
}

/** `{{` の直後から対応する `}}` までを読み取る。クォート内の `}}` は区切りとして扱わない。 */
private fun readTagContent(
    cursor: Cursor,
    fail: (String) -> Nothing,
): String {
    val sb = StringBuilder()
    var inString: Char? = null
    while (true) {
        if (cursor.atEnd()) fail("unterminated tag: missing closing '}}'")
        if (inString == null && cursor.startsWith("}}")) {
            cursor.advance()
            cursor.advance()
            return sb.toString()
        }
        val c = cursor.peek()!!
        when {
            inString != null && c == '\\' && cursor.peek(1) != null -> {
                sb.append(cursor.advance())
                sb.append(cursor.advance())
            }
            inString != null && c == inString -> {
                inString = null
                sb.append(cursor.advance())
            }
            inString == null && (c == '"' || c == '\'') -> {
                inString = c
                sb.append(cursor.advance())
            }
            else -> sb.append(cursor.advance())
        }
    }
}
