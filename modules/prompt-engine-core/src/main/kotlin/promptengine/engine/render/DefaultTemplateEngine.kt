package promptengine.engine.render

import promptengine.domain.context.ContextBindingSet
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.TemplateEngine
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.variable.BindingSet

/**
 * PE標準のTemplate Engine実装（`id() = "pe-tmpl/1"`、設計書§2.9・実装ガイド§6.7）。
 *
 * [expand]は決定的でなければならない。[variableBindings]・[contextBindings]は
 * ピンポイントのキー参照（[Scope.resolve]）のみで読み、Map全体の反復には依存しない
 * （ADR-0013決定2）。
 */
class DefaultTemplateEngine : TemplateEngine {
    override fun id(): String = ENGINE_ID

    override fun expand(
        body: List<PromptAst>,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<RenderedMessage> {
        val scope = Scope(emptyMap(), variableBindings, contextBindings)
        return MessageWalker(scope).walk(body)
    }

    companion object {
        const val ENGINE_ID = "pe-tmpl/1"
    }
}
