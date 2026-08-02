package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage

/**
 * Stage 10（Response Parsing、設計書§2.6）。[ExecutionStage]が[promptengine.domain.execution.ExecutionEngine]
 * 経由で解析まで完了させているため（ADR-0014決定6・ADR-0015決定2）、
 * `context.executionOutcome.parsedOutput`を`context.parsedOutput`へ転記するのみ。
 */
class ResponseParsingStage : PipelineStage {
    override val name: String = "ResponseParsing"

    override fun execute(context: PipelineContext): PipelineContext {
        val outcome =
            checkNotNull(context.executionOutcome) {
                "ResponseParsingStage requires executionOutcome (Stage 9 Execution must run first)"
            }
        return context.copy(parsedOutput = outcome.parsedOutput)
    }
}
