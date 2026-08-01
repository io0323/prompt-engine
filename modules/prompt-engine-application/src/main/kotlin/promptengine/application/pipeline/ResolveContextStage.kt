package promptengine.application.pipeline

import promptengine.domain.context.ContextResolverChain
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineStage

/** Stage 5（Resolve Context、設計書§2.6）。[ContextResolverChain]へ委譲する。 */
class ResolveContextStage(private val contextResolverChain: ContextResolverChain) : PipelineStage {
    override val name: String = "ResolveContext"

    override fun execute(context: PipelineContext): PipelineContext {
        val compiled =
            checkNotNull(context.compiled) {
                "ResolveContextStage requires compiled (Stage 2 Merge must run first)"
            }
        val bindings = contextResolverChain.resolve(compiled.contextRequirements, context.request.variableResolution)
        return context.copy(contextBindings = bindings)
    }
}
