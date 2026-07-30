package promptengine.domain.render

import promptengine.domain.context.ContextBindingSet
import promptengine.domain.template.ast.PromptAst
import promptengine.domain.variable.BindingSet

/**
 * ASTをmessagesへ展開するPlugin（設計書§2.9「Template EngineはPlugin」、§3.4の
 * `TemplateEngine.expand`をADR-0013決定10で実際の型へ読み替えたもの）。
 *
 * 既定実装（`id() = "pe-tmpl/1"`）は`prompt-engine-core`の`DefaultTemplateEngine`。
 * [RenderEngine]は必ずこのInterface経由でASTを展開し、直接AST走査を行わない
 * （実装ガイド§6.7「TemplateEngineインターフェース経由でのみ展開する」、差替可能性の担保）。
 *
 * [expand]は決定的でなければならない（同一[body]・[variableBindings]・[contextBindings]から
 * 常に同一の結果、設計書§2.9）。実装はMap/Setの反復順序に依存する形で出力を組み立てては
 * ならない（ADR-0013決定2）。
 */
interface TemplateEngine {
    /** このTemplate Engineを一意に識別するID（例: `"pe-tmpl/1"`）。[RenderedPrompt.renderHash]に混入する。 */
    fun id(): String

    fun expand(
        body: List<PromptAst>,
        variableBindings: BindingSet,
        contextBindings: ContextBindingSet,
    ): List<RenderedMessage>
}
