package promptengine.engine.resolver

import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableSource

/**
 * `source=ENVIRONMENT`の変数を[PromptRequest.environmentVariables]（PE環境設定、
 * env毎に差替、設計書§2.8「Environment」）から解決する。
 */
class EnvironmentResolver : VariableResolver {
    override fun resolve(
        definition: VariableDefinition,
        request: PromptRequest,
    ): Any? {
        if (definition.source != VariableSource.ENVIRONMENT) return null
        return request.environmentVariables[definition.name]
    }
}
