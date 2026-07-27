package promptengine.domain.template.ast

/**
 * 式の被演算子（設計書§15.1「式はプロパティ参照とパイプフィルタのみ」）。
 * 任意コード実行を防ぐため、値はプロパティ参照かリテラルに限定する。
 */
sealed interface ExpressionOperand

/** `context.application.serviceName` のようなドット区切りのプロパティ参照。 */
data class PropertyRef(val path: List<String>) : ExpressionOperand {
    init {
        require(path.isNotEmpty()) { "path must not be empty" }
        require(path.all { it.isNotBlank() }) { "path segments must not be blank: $path" }
    }
}

/** 式のリテラル被演算子（文字列/数値/真偽値）。プロパティ参照ではなく即値であることを表す。 */
sealed interface Literal : ExpressionOperand

/** `"..."` / `'...'`（バックスラッシュエスケープ対応）で表される文字列リテラル。 */
data class StringLiteral(val value: String) : Literal

/** 整数・小数（負数可）の数値リテラル。 */
data class NumberLiteral(val value: Double) : Literal

/** bareword `true` / `false` の真偽値リテラル。 */
data class BooleanLiteral(val value: Boolean) : Literal

/** パイプフィルタ1段（`upper` / `truncate(100)`）。 */
data class FilterCall(
    val name: String,
    val arguments: List<ExpressionOperand> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "filter name must not be blank" }
    }
}

/** `{{ name | upper | truncate(100) }}` の `name | upper | truncate(100)` 部分。 */
data class Expression(
    val operand: ExpressionOperand,
    val filters: List<FilterCall> = emptyList(),
)
