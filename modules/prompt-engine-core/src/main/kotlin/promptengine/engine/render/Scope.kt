package promptengine.engine.render

import promptengine.domain.context.ContextBindingSet
import promptengine.domain.template.ast.PropertyRef
import promptengine.domain.variable.BindingSet

/**
 * 式評価中の変数解決スコープ（[DefaultTemplateEngine]専用）。
 *
 * [locals]は`EachNode`のループ変数束縛（設計書のExpressionSubstitutionTest
 * 「eachのループ変数名は束縛をシャドーイングする」と同じ規則をRender時にも適用する）。
 * `context.*`参照は常に[contextBindings]から解決し、[locals]の影響を受けない
 * （`"context"`という名前がループ変数名と衝突するケースは想定しない）。
 */
internal class Scope(
    private val locals: Map<String, Any>,
    private val variableBindings: BindingSet,
    private val contextBindings: ContextBindingSet,
) {
    fun withLocal(
        name: String,
        value: Any,
    ): Scope = Scope(locals + (name to value), variableBindings, contextBindings)

    fun resolve(ref: PropertyRef): Any? =
        if (ref.path.first() == CONTEXT_SEGMENT) resolveContext(ref) else resolveLocalOrVariable(ref)

    private fun resolveContext(ref: PropertyRef): Any? =
        if (ref.path.size < MIN_CONTEXT_PATH_SIZE) {
            null
        } else {
            contextBindings.values[ref.path.drop(1).joinToString(separator = ".")]
        }

    private fun resolveLocalOrVariable(ref: PropertyRef): Any? {
        val base: Any? = locals[ref.path.first()] ?: variableBindings[ref.path.first()]
        return ref.path.drop(1).fold(base) { acc, segment -> (acc as? Map<*, *>)?.get(segment) }
    }

    private companion object {
        const val CONTEXT_SEGMENT = "context"
        const val MIN_CONTEXT_PATH_SIZE = 3
    }
}
