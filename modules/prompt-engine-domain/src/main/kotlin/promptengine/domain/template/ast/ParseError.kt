package promptengine.domain.template.ast

/**
 * ParseErrorの種別。SYNTAX_ERRORは本文/Front Matterの文法違反、INPUT_TOO_LARGE/
 * NESTING_TOO_DEEPはパーサ側の上限超過（専用エラーとして区別する。設計書のP3実装ガイド
 * 「入力サイズ上限とネスト深さ上限をパーサ側にも設ける」）。HTTPへの写像はいずれも
 * §13.3 PARSE_FAILED（400）に集約される。
 */
enum class ParseErrorKind {
    SYNTAX_ERROR,
    INPUT_TOO_LARGE,
    NESTING_TOO_DEEP,
}

/**
 * 構文エラー1件。行・列（1始まり）に加え、該当行のテキストとキャレット表示を含める
 * （P3実装ガイド「位置情報とエラー行の抜粋を正確に出す」）。
 */
data class ParseError(
    val kind: ParseErrorKind,
    val line: Int,
    val column: Int,
    val lineText: String,
    val message: String,
) {
    init {
        require(line >= 1) { "line must be >= 1: $line" }
        require(column >= 1) { "column must be >= 1: $column" }
    }

    /** 該当行のテキストと、列位置を指すキャレット（`^`）の2行表示。 */
    fun caretExcerpt(): String = "$lineText\n${" ".repeat(column - 1)}^"
}

/**
 * Prompt DSLの構文解析失敗を表す例外。最初に検出した[ParseError]1件を保持する
 * （構文エラーは最初の1件で停止する方針。P3実装ガイド）。メッセージには
 * 種別・位置・キャレット表示を含める。
 */
class PromptDslParseException(val error: ParseError, cause: Throwable? = null) :
    Exception(
        "${error.kind} at line ${error.line}, column ${error.column}: ${error.message}\n${error.caretExcerpt()}",
        cause,
    )
