package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.variable.VariableResolverChain

/** Stage 4（Resolve Variables、設計書§2.6）。[VariableResolverChain]へ委譲する。 */
class ResolveVariablesStage(private val variableResolverChain: VariableResolverChain) : PipelineStage {
    override val name: String = "ResolveVariables"

    override fun execute(context: PipelineContext): PipelineContext {
        val compiled =
            checkNotNull(context.compiled) { "ResolveVariablesStage requires compiled (Stage 2 Merge must run first)" }
        val bindings = variableResolverChain.resolveAll(compiled.variables, context.request.variableResolution)
        return context.copy(variableBindings = bindings)
    }
}
