package promptengine.engine.resolver

import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource

/**
 * `source=USER`の変数を[PromptRequest.userVariables]（CIAP Subject単位、呼出元供給）
 * から解決する（設計書§2.8「User」）。
 */
class UserResolver : VariableResolver {
    override fun resolve(
        definition: VariableDefinition,
        request: PromptRequest,
    ): Any? {
        if (definition.source != VariableSource.USER) return null
        return request.userVariables[definition.name]
    }
}
