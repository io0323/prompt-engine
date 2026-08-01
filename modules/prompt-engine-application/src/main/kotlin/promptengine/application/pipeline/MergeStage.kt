package promptengine.application.pipeline

import promptengine.domain.composition.CompositionMode
import promptengine.domain.composition.CompositionService
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineStage

/**
 * Stage 2（Merge、設計書§2.6）。[CompositionService.compile]はextends解決・import/include
 * 解決・macro展開を1回の呼び出しでまとめて行う設計が既に確立している（P3c、ADR-0009/0010）ため、
 * このStageがStage 3（Import）分も含めて委譲する（ADR-0015決定2の追加適用。[ImportStage]は
 * 素通しステージ）。
 */
class MergeStage(private val compositionService: CompositionService) : PipelineStage {
    override val name: String = "Merge"

    override fun execute(context: PipelineContext): PipelineContext {
        val promptVersion =
            checkNotNull(context.promptVersion) { "MergeStage requires promptVersion (Stage 1 Load must run first)" }
        val compositionMode =
            if (context.mode == PipelineMode.COMPILE_ONLY) CompositionMode.COMPILE_ONLY else CompositionMode.STANDARD
        val compiled = compositionService.compile(context.request.promptKey, promptVersion, compositionMode)
        return context.copy(compiled = compiled)
    }
}
