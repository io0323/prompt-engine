package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineStage

/**
 * [PipelineMode]に応じて実行対象の[PipelineStage]列を選択する（設計書§2.6、
 * ADR-0015決定6）。
 *
 * [stages]は§2.6の順序（Load, Merge, Import, ResolveVariables, ResolveContext,
 * Validation, Optimization, Rendering, Execution, ResponseParsing, Evaluation, Audit）で
 * 12件揃っていることをコンストラクタで検証する。並び自体の構築（DI結線）は
 * `prompt-engine-bootstrap`（P9スコープ）が担う。
 */
class PipelineFactory(private val stages: List<PipelineStage>) {
    init {
        require(stages.size == STAGE_COUNT) {
            "PipelineFactory requires exactly $STAGE_COUNT stages (§2.6 order), got ${stages.size}"
        }
    }

    fun stagesFor(mode: PipelineMode): List<PipelineStage> =
        when (mode) {
            PipelineMode.RENDER_ONLY -> stages.take(RENDER_ONLY_STAGE_COUNT)
            PipelineMode.FULL_EXECUTION -> stages
            PipelineMode.COMPILE_ONLY -> stages.take(COMPILE_ONLY_LEADING_STAGE_COUNT) + stages[VALIDATION_STAGE_INDEX]
        }

    companion object {
        private const val STAGE_COUNT = 12
        private const val RENDER_ONLY_STAGE_COUNT = 8
        private const val COMPILE_ONLY_LEADING_STAGE_COUNT = 3
        private const val VALIDATION_STAGE_INDEX = 5
    }
}
