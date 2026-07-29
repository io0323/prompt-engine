package promptengine.engine.resolver

import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource

/**
 * `source=WORKFLOW`の変数を[PromptRequest.workflowVariables]（AACPから受領、
 * workflow実行ID単位）から解決する（設計書§2.8「Workflow」）。
 */
class WorkflowResolver : VariableResolver {
    override fun resolve(
        definition: VariableDefinition,
        request: PromptRequest,
    ): Any? {
        if (definition.source != VariableSource.WORKFLOW) return null
        return request.workflowVariables[definition.name]
    }
}
