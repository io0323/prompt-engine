package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage

/**
 * Stage 3（Import、設計書§2.6）。[MergeStage]が[promptengine.domain.composition.CompositionService.compile]
 * 経由でextends/import/include解決を既に済ませているため（ADR-0015決定2の追加適用）、
 * このStageは素通し（`context`をそのまま返す）。
 */
class ImportStage : PipelineStage {
    override val name: String = "Import"

    override fun execute(context: PipelineContext): PipelineContext = context
}
