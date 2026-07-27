package promptengine.engine.parser.internal

import promptengine.domain.template.ast.BlockRole
import promptengine.domain.template.ast.ExprNode
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.IncludeNode
import promptengine.domain.template.ast.MacroCallNode

private val MACRO_CALL_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*\\(.*\\)$")
private val BLOCK_OPEN_PATTERN = Regex("^#block(\\s+.*)?$")
private val EACH_OPEN_PATTERN = Regex("^#each(\\s+.*)?$")
private val IF_OPEN_PATTERN = Regex("^#if(\\s+.*)?$")
private val IDENTIFIER_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/**
 * `{{ }}` タグの中身（trim済み文字列）を種別ごとに解析して[TagResult]を返す。
 * 位置情報はタグ全体の開始位置（`{{` の位置）に丸める（[BodyParser]参照）。
 */
internal class TagContentParser(private val fail: (String) -> Nothing) {
    fun parse(trimmed: String): TagResult =
        when {
            trimmed.isEmpty() -> fail("empty tag '{{}}'")
            trimmed.startsWith("!--") -> parseComment(trimmed)
            trimmed.startsWith(">") -> parseInclude(trimmed.removePrefix(">").trim())
            trimmed == "else" -> TagResult.Terminator("else")
            trimmed == "/if" -> TagResult.Terminator("/if")
            trimmed == "/each" -> TagResult.Terminator("/each")
            trimmed == "/block" -> TagResult.Terminator("/block")
            IF_OPEN_PATTERN.matches(trimmed) -> parseIfOpen(trimmed.removePrefix("#if").trim())
            EACH_OPEN_PATTERN.matches(trimmed) -> parseEachOpen(trimmed.removePrefix("#each").trim())
            BLOCK_OPEN_PATTERN.matches(trimmed) -> parseBlockOpen(trimmed.removePrefix("#block").trim())
            MACRO_CALL_PATTERN.matches(trimmed) -> parseMacroCall(trimmed)
            else -> TagResult.Node(ExprNode(parseExpression(trimmed)))
        }

    private fun parseComment(trimmed: String): TagResult {
        if (!trimmed.endsWith("--") || trimmed.length < "!----".length) {
            fail("unterminated comment: expected '--' before '}}'")
        }
        return TagResult.Skip
    }

    private fun parseIfOpen(conditionText: String): TagResult {
        if (conditionText.isEmpty()) fail("'{{#if}}' requires a condition expression")
        return TagResult.IfOpen(parseExpression(conditionText))
    }

    private fun parseEachOpen(rest: String): TagResult {
        val separator = " as "
        val separatorIndex = rest.indexOf(separator)
        if (separatorIndex < 0) fail("'{{#each}}' must be of the form '#each <list> as <item>'")
        val iterableText = rest.substring(0, separatorIndex).trim()
        val itemName = rest.substring(separatorIndex + separator.length).trim()
        // 呼出元（TagContentParser.parse）が渡すrestは既にtrim()済みで先頭・末尾に
        // 空白を持たないため、iterableText/itemNameが空文字列になるのは
        // separatorIndexがrestの先頭または末尾に一致する場合のみだが、それは
        // restが空白で始まる/終わることを意味し矛盾する（監査時点で到達不能と
        // 確認済み、判定式から削除）。itemNameが空文字列の場合もIDENTIFIER_PATTERNが
        // 最低1文字を要求するため、いずれにせよ下のmatches()で捕捉される。
        if (!IDENTIFIER_PATTERN.matches(itemName)) {
            fail("'{{#each}}' must be of the form '#each <list> as <item>'")
        }
        return TagResult.EachOpen(parseExpression(iterableText), itemName)
    }

    private fun parseBlockOpen(roleText: String): TagResult {
        val role =
            when (roleText) {
                "system" -> BlockRole.SYSTEM
                "user" -> BlockRole.USER
                "assistant" -> BlockRole.ASSISTANT
                else -> fail("'{{#block}}' role must be one of system/user/assistant but was '$roleText'")
            }
        return TagResult.BlockOpen(role)
    }

    private fun parseInclude(text: String): TagResult {
        val tokens = splitTopLevelWhitespace(text)
        if (tokens.isEmpty()) fail("'{{> }}' include target must not be empty")
        val targetToken = tokens.first()
        val at = targetToken.indexOf('@')
        val target = if (at < 0) targetToken else targetToken.substring(0, at)
        val versionRange = if (at < 0) null else targetToken.substring(at + 1)
        if (target.isEmpty()) fail("'{{> }}' include target must not be empty")

        val bindings = mutableMapOf<String, Expression>()
        for (token in tokens.drop(1)) {
            val eq = token.indexOf('=')
            if (eq <= 0) fail("expected 'k=v' binding in include but got '$token'")
            bindings[token.substring(0, eq)] = parseExpression(token.substring(eq + 1))
        }
        return TagResult.Node(IncludeNode(target, versionRange, bindings))
    }

    private fun parseMacroCall(trimmed: String): TagResult {
        val openParen = trimmed.indexOf('(')
        val name = trimmed.substring(0, openParen)
        val argsText = trimmed.substring(openParen + 1, trimmed.length - 1).trim()
        val arguments = mutableMapOf<String, Expression>()
        for (token in splitTopLevelCommas(argsText)) {
            val eq = token.indexOf('=')
            if (eq <= 0) fail("macro argument must be of the form 'name=value' but was '$token'")
            arguments[token.substring(0, eq).trim()] = parseExpression(token.substring(eq + 1).trim())
        }
        return TagResult.Node(MacroCallNode(name, arguments))
    }

    private fun parseExpression(text: String): Expression =
        ExpressionTextParser(text) { message -> fail(message) }.parse()
}

/**
 * クォート内の `\x` エスケープを認識しつつ、クォート外の空白でトークン分割する。
 * [readTagContent]と同じエスケープ規則にしておかないと、`note="a\" b"`のように
 * エスケープされた `"` の直後に空白があるケースで誤って分割してしまう
 * （監査で発見・修正）。
 */
private fun splitTopLevelWhitespace(text: String): List<String> {
    val tokens = mutableListOf<String>()
    val sb = StringBuilder()
    var inString: Char? = null
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inString != null && c == '\\' && i + 1 < text.length -> {
                sb.append(c).append(text[i + 1])
                i += 2
                continue
            }
            inString != null -> {
                sb.append(c)
                if (c == inString) inString = null
            }
            c == '"' || c == '\'' -> {
                inString = c
                sb.append(c)
            }
            c.isWhitespace() -> {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
            }
            else -> sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty()) tokens.add(sb.toString())
    return tokens
}

/** [splitTopLevelWhitespace]と同じエスケープ規則で、クォート外のカンマでトークン分割する。 */
private fun splitTopLevelCommas(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val tokens = mutableListOf<String>()
    val sb = StringBuilder()
    var inString: Char? = null
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inString != null && c == '\\' && i + 1 < text.length -> {
                sb.append(c).append(text[i + 1])
                i += 2
                continue
            }
            inString != null -> {
                sb.append(c)
                if (c == inString) inString = null
            }
            c == '"' || c == '\'' -> {
                inString = c
                sb.append(c)
            }
            c == ',' -> {
                tokens.add(sb.toString())
                sb.clear()
            }
            else -> sb.append(c)
        }
        i++
    }
    tokens.add(sb.toString())
    return tokens.map { it.trim() }.filter { it.isNotEmpty() }
}
