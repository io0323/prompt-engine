package promptengine.domain.template.ast

/**
 * Prompt DSL本文のASTノード（設計書§3.5 Composite「PromptAst（Fragment/Includeノードの
 * Tree）」、§15.1 構文要素）。IfNode/EachNode/BlockNodeは子ノード列を持ち再帰的な木を成す。
 */
sealed interface PromptAst

/** リテラルテキスト（`{{ }}`タグ以外の生文字列）。 */
data class TextNode(val text: String) : PromptAst

/** `{{ expr }}`（式の値のテキスト置換）。 */
data class ExprNode(val expression: Expression) : PromptAst

/** `{{#if}}...{{else}}...{{/if}}`。`condition`が真ならthenBranch、偽ならelseBranchを描画する。 */
data class IfNode(
    val condition: Expression,
    val thenBranch: List<PromptAst>,
    val elseBranch: List<PromptAst> = emptyList(),
) : PromptAst

/** `{{#each list as item}}...{{/each}}`。`iterable`の各要素を`itemName`で束縛して`body`を繰り返す。 */
data class EachNode(
    val iterable: Expression,
    val itemName: String,
    val body: List<PromptAst>,
) : PromptAst {
    init {
        require(itemName.isNotBlank()) { "itemName must not be blank" }
    }
}

/** roleは設計書§15.1が定義する固定集合（system/user/assistant）に限定する。 */
enum class BlockRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

/** `{{#block role}}...{{/block}}`（設計書§15.1）。roleごとにメッセージを構成する単位。 */
data class BlockNode(
    val role: BlockRole,
    val body: List<PromptAst>,
) : PromptAst

/**
 * `{{> <alias|fragmentKey>[@versionRange] [k=v ...] }}`（設計書§15.4/§15.5）。
 * `target` はalias名・fragmentKey・`prompt:other/prompt-key` のいずれかの生表記であり、
 * どの形式かの判定（解決）は3c（Composition解決）の責務。
 */
data class IncludeNode(
    val target: String,
    val versionRange: String? = null,
    val bindings: Map<String, Expression> = emptyMap(),
) : PromptAst {
    init {
        require(target.isNotBlank()) { "target must not be blank" }
    }
}

/** `{{ bulletList(items=faqItems) }}`（設計書§15.6）。マクロ本体の展開は3cの責務。 */
data class MacroCallNode(
    val name: String,
    val arguments: Map<String, Expression> = emptyMap(),
) : PromptAst {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
    }
}
