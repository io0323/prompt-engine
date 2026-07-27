package promptengine.engine.parser.internal

import promptengine.domain.template.ast.BooleanLiteral
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.ExpressionOperand
import promptengine.domain.template.ast.FilterCall
import promptengine.domain.template.ast.NumberLiteral
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.StringLiteral

/**
 * 式の文法（設計書§15.1「式はプロパティ参照とパイプフィルタのみ」）を1個のタグ内容
 * 文字列から解析する。位置情報はタグ全体の開始位置に丸める（[BodyParser]参照）ため、
 * このクラス自身は行・列を追跡しない。
 */
internal class ExpressionTextParser(private val text: String, private val onError: (String) -> Nothing) {
    private var i = 0

    fun parse(): Expression {
        skipWs()
        val operand = parseOperand()
        skipWs()
        val filters = mutableListOf<FilterCall>()
        while (peek() == '|') {
            i++
            skipWs()
            filters.add(parseFilterCall())
            skipWs()
        }
        if (i != text.length) onError("unexpected trailing characters in expression: '${text.substring(i)}'")
        return Expression(operand, filters)
    }

    private fun parseFilterCall(): FilterCall {
        val name = parseIdentifier()
        skipWs()
        val args = if (peek() == '(') parseFilterArgs() else emptyList()
        return FilterCall(name, args)
    }

    private fun parseFilterArgs(): List<ExpressionOperand> {
        i++ // 開き括弧 '(' を消費する
        skipWs()
        val args = mutableListOf<ExpressionOperand>()
        if (peek() == ')') {
            i++
            return args
        }
        while (true) {
            args.add(parseOperand())
            skipWs()
            when (peek()) {
                ',' -> {
                    i++
                    skipWs()
                }
                ')' -> {
                    i++
                    return args
                }
                else -> onError("expected ',' or ')' in filter arguments")
            }
        }
    }

    private fun parseOperand(): ExpressionOperand {
        skipWs()
        val c = peek() ?: onError("expected a value but reached end of expression")
        return when {
            c == '"' || c == '\'' -> parseStringLiteral()
            c.isDigit() || (c == '-' && peek(1)?.isDigit() == true) -> parseNumberLiteral()
            c.isLetter() || c == '_' -> {
                val path = parsePropertyPath()
                if (path.path.size == 1 && (path.path[0] == "true" || path.path[0] == "false")) {
                    BooleanLiteral(path.path[0] == "true")
                } else {
                    path
                }
            }
            else -> onError("unexpected character '$c' in expression")
        }
    }

    private fun parseStringLiteral(): StringLiteral {
        val quote = text[i]
        i++
        val sb = StringBuilder()
        while (true) {
            // [BodyParser]のreadTagContentが同一のクォート対応規則でタグ内容を切り出すため、
            // このクラスに渡ってくる文字列内のクォートは常に対応が取れている（監査時点で
            // 到達不能と確認済み）。それでも、将来このクラスが読取り前のテキストへ直接
            // 呼び出される経路が追加された場合の`StringIndexOutOfBoundsException`を防ぐ
            // 安全網として残す。
            if (i >= text.length) onError("unterminated string literal")
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    sb.append(text[i + 1])
                    i += 2
                }
                c == quote -> {
                    i++
                    return StringLiteral(sb.toString())
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
    }

    private fun parseNumberLiteral(): NumberLiteral {
        val start = i
        if (peek() == '-') i++
        while (peek()?.isDigit() == true) i++
        if (peek() == '.') {
            i++
            while (peek()?.isDigit() == true) i++
        }
        val token = text.substring(start, i)
        // parseOperandの呼出条件（先頭が数字、または'-'の次が数字であること）により、
        // tokenは常に有効な数値表記になる（監査時点で到達不能と確認済み）。それでも、
        // `!!`や無条件toDouble()による素の NumberFormatException 漏出よりは、この
        // ParseError経路を残す方が呼出側の契約（常にPromptDslParseExceptionを投げる）
        // に対して安全なので削除しない。
        val value = token.toDoubleOrNull() ?: onError("invalid number literal '$token'")
        return NumberLiteral(value)
    }

    private fun parseIdentifier(): String {
        val start = i
        if (peek()?.let { it.isLetter() || it == '_' } != true) onError("expected an identifier")
        i++
        while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) i++
        return text.substring(start, i)
    }

    private fun parsePropertyPath(): PropertyRef {
        val segments = mutableListOf(parseIdentifier())
        while (peek() == '.') {
            i++
            segments.add(parseIdentifier())
        }
        return PropertyRef(segments)
    }

    private fun skipWs() {
        while (peek()?.isWhitespace() == true) i++
    }

    private fun peek(offset: Int = 0): Char? {
        val index = i + offset
        return if (index in text.indices) text[index] else null
    }
}
