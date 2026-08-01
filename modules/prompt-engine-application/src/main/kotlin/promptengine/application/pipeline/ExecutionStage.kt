package promptengine.application.pipeline

import promptengine.domain.execution.ExecutionEngine
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage

/**
 * Stage 9（Execution、設計書§2.6）。[ExecutionEngine]へ委譲する。
 *
 * [ExecutionEngine.run]は実行(ステージ9)と解析(ステージ10)を1回の呼び出しで行う
 * （ADR-0014決定6・ADR-0015決定2）。結果は`PipelineContext.executionOutcome`へ格納し、
 * [ResponseParsingStage]がそこから`parsedOutput`を転記する。
 */
class ExecutionStage(private val executionEngine: ExecutionEngine) : PipelineStage {
    override val name: String = "Execution"

    override fun execute(context: PipelineContext): PipelineContext {
        val rendered =
            checkNotNull(context.rendered) { "ExecutionStage requires rendered (Stage 8 Rendering must run first)" }
        val policy =
            checkNotNull(context.request.executionPolicy) {
                "ExecutionStage requires request.executionPolicy (FULL_EXECUTION mode only)"
            }
        val outcome = executionEngine.run(rendered, policy, context.request.outputSchema, context.request.budget)
        return context.copy(executionOutcome = outcome)
    }
}
