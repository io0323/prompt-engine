package promptengine.engine.parser.internal

internal data class Position(val line: Int, val column: Int)

/**
 * 本文テキストを1文字ずつ読み進めながら行・列（1始まり）を追跡するカーソル。
 * `startLine` はFront Matter分をスキップした本文の開始行（ファイル全体基準）。
 */
internal class Cursor(private val text: String, startLine: Int) {
    var pos: Int = 0
        private set
    var line: Int = startLine
        private set
    var column: Int = 1
        private set

    fun atEnd(): Boolean = pos >= text.length

    fun peek(offset: Int = 0): Char? {
        val index = pos + offset
        return if (index in text.indices) text[index] else null
    }

    fun startsWith(literal: String): Boolean = text.startsWith(literal, pos)

    fun position(): Position = Position(line, column)

    fun advance(): Char {
        val c = text[pos]
        pos++
        if (c == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        return c
    }
}
