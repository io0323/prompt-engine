package promptengine.engine.render

import promptengine.domain.shared.SensitiveValue
import promptengine.domain.template.ast.BooleanLiteral
import promptengine.domain.template.ast.Expression
import promptengine.domain.template.ast.ExpressionOperand
import promptengine.domain.template.ast.FilterCall
import promptengine.domain.template.ast.NumberLiteral
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.template.ast.StringLiteral
import java.util.Locale

/**
 * `{{ expr }}`の式評価（設計書§15.1「式はプロパティ参照とパイプフィルタのみ」）。
 * DSLの本格的な実行時評価はこのリポジトリで初めて実装する箇所である
 * （ADR-0013。P3cのExpressionSubstitutionはマクロ引数のCompile時テキスト置換であり、
 * 実行時のVariable/Context束縛値による評価ではない）。
 *
 * 既知フィルタ: `upper`/`lower`（[Locale.ROOT]固定、ADR-0013決定2でロケール依存を排除）、
 * `trim`、`truncate(n)`、`default(fallback)`（値が解決できなかった場合のみ適用）。
 * 未知フィルタは無視する（前方互換、ADR-0012決定6と同様の方針）。
 */
internal object ExpressionEvaluator {
    fun evaluate(
        expression: Expression,
        scope: Scope,
    ): String {
        var text = valueToText(resolveOperand(expression.operand, scope))
        for (filter in expression.filters) {
            text = applyFilter(filter, text, scope)
        }
        return text ?: ""
    }

    fun resolveOperand(
        operand: ExpressionOperand,
        scope: Scope,
    ): Any? =
        when (operand) {
            is PropertyRef -> scope.resolve(operand)
            is StringLiteral -> operand.value
            is NumberLiteral -> operand.value
            is BooleanLiteral -> operand.value
        }

    fun isTruthy(value: Any?): Boolean =
        when (value) {
            null -> false
            is Boolean -> value
            is String -> value.isNotEmpty()
            is Double -> value != 0.0
            else -> true
        }

    fun valueToText(value: Any?): String? =
        when (value) {
            null -> null
            is SensitiveValue -> value.expose()
            is Double -> formatNumber(value)
            else -> value.toString()
        }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun applyFilter(
        filter: FilterCall,
        text: String?,
        scope: Scope,
    ): String? =
        when (filter.name) {
            "upper" -> text?.uppercase(Locale.ROOT)
            "lower" -> text?.lowercase(Locale.ROOT)
            "trim" -> text?.trim()
            "truncate" -> truncate(text, filter, scope)
            "default" -> text ?: filter.arguments.firstOrNull()?.let { valueToText(resolveOperand(it, scope)) }
            else -> text
        }

    private fun truncate(
        text: String?,
        filter: FilterCall,
        scope: Scope,
    ): String? {
        val limitArg = filter.arguments.firstOrNull()?.let { resolveOperand(it, scope) } as? Number
        val limit = limitArg?.toInt()
        return when {
            text == null -> null
            limit == null || limit !in 0 until text.length -> text
            else -> text.substring(0, limit)
        }
    }
}
