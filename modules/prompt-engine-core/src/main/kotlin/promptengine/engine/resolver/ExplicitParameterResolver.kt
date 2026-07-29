package promptengine.engine.resolver

import promptengine.domain.variable.VariableDefinition

/**
 * 呼出パラメータによる明示的な値の上書き（設計書§2.8「Explicit Parameter」、最優先）。
 * `definition.source`を見ない ── どの`source`が宣言された変数であっても、呼出側が
 * 明示的に値を渡していれば常にそれが勝つ。
 */
class ExplicitParameterResolver : VariableResolver {
    override fun resolve(
        definition: VariableDefinition,
        request: PromptRequest,
    ): Any? = request.explicitParameters[definition.name]
}
