package promptengine.domain.composition

import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersion

/**
 * Compositionの解決順（設計書§15.3「extends → import → include → macro展開」）を
 * PromptVersion単位で実行し、[CompiledPrompt] を生成するDomain Service（設計書§4.5）。
 * 実装は`prompt-engine-core`（`promptengine.engine.compiler.CompositionServiceImpl`）が持つ
 * （domainはDSL Parserに依存できないため、ADR-0010決定9）。
 */
interface CompositionService {
    /** [promptKey]の[promptVersion]を[mode]でコンパイルする。 */
    fun compile(
        promptKey: PromptKey,
        promptVersion: PromptVersion,
        mode: CompositionMode,
    ): CompiledPrompt
}
