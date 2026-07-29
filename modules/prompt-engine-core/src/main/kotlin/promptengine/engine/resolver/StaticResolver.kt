package promptengine.engine.resolver

import promptengine.domain.shared.PromptRequest
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableResolver
import promptengine.domain.variable.VariableSource

/** `source=STATIC`の変数をDSL宣言の`default`から解決する（設計書§2.8「Static」）。 */
class StaticResolver : VariableResolver {
    override fun resolve(
        definition: VariableDefinition,
        request: PromptRequest,
    ): Any? {
        if (definition.source != VariableSource.STATIC) return null
        return definition.default
    }
}
