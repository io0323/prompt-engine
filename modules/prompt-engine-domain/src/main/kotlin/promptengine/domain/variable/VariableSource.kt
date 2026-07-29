package promptengine.domain.variable

/**
 * DSL変数の解決経路（設計書§2.8・§15.2、ADR-0011）。
 *
 * Resolver Chain（§2.8「Explicit Parameter → Static → User → Workflow → Environment → Secret」）の
 * うちExplicit Parameter以外の5種は、[VariableDefinition.source] がそれぞれの担当種別と
 * 一致する変数のみを解決対象とする。Explicit Parameterによる上書きはsourceによらず常に
 * 最優先されるため、この列挙には含めない（Resolver自体は`RUNTIME`変数を主対象とするが、
 * 判定はExplicit Parameter Resolverが「値が渡されたかどうか」だけで行う）。
 */
enum class VariableSource {
    STATIC,
    RUNTIME,
    SECRET,
    ENVIRONMENT,
    USER,
    WORKFLOW,
}
