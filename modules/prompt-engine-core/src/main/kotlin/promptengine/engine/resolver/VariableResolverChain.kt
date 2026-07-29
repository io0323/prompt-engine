package promptengine.engine.resolver

import promptengine.domain.shared.SensitiveValue
import promptengine.domain.variable.BindingSet
import promptengine.domain.variable.SecretManagerAdapter
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableUnresolvedException

/**
 * Variable Resolver Chain（Chain of Responsibility、設計書§3.3・§5.3シーケンス）。
 *
 * [resolvers] は先勝ちの優先順位で並んだ[VariableResolver]の列である。既定は
 * [standard]（Explicit Parameter → Static → User → Workflow → Environment → Secret、
 * 設計書§2.8）だが、[resolvers]を直接差し替えることでPlugin単位の入替に対応する
 * （設計書§16-2「拡張ポイント#2」、PluginManager自体の実装は後続フェーズ）。
 *
 * [resolveAll] は`required`の変数が1件でも解決できなければ[VariableUnresolvedException]を
 * 投げる。設計書§5.3「未解決の変数名を1つ目で止めずに全て列挙して返すこと」の通り、
 * 解決ループ全体を最後まで実行してから未解決名をまとめて例外に含める。
 *
 * `sensitive=true`の変数は、どのResolverが解決した値であっても
 * （[SecretResolver]が返す値は既に[SensitiveValue]でラップ済みだが、それ以外の
 * Resolver経由で解決される可能性も型上否定できないため）[BindingSet]へ格納する前に
 * 必ず[SensitiveValue]でラップする。これにより生の秘匿値がBindingSetの内部Mapに
 * 一切入り込まない。
 *
 * Resolver（特に[SecretResolver]）が投げた例外は捕捉せずそのまま伝播させる
 * （ADR-0011。Secret Manager自体のインフラ障害は`VARIABLE_UNRESOLVED`に混ぜない）。
 */
class VariableResolverChain(
    private val resolvers: List<VariableResolver>,
) {
    fun resolveAll(
        definitions: List<VariableDefinition>,
        request: PromptRequest,
    ): BindingSet {
        val bindings = linkedMapOf<String, Any>()
        val missing = mutableListOf<String>()

        for (definition in definitions) {
            val resolved = resolveOne(definition, request)
            when {
                resolved != null -> bindings[definition.name] = wrapIfSensitive(definition, resolved)
                definition.required -> missing += definition.name
            }
        }

        if (missing.isNotEmpty()) {
            throw VariableUnresolvedException(missing)
        }
        return BindingSet(bindings)
    }

    private fun resolveOne(
        definition: VariableDefinition,
        request: PromptRequest,
    ): Any? {
        for (resolver in resolvers) {
            val value = resolver.resolve(definition, request)
            if (value != null) return value
        }
        return null
    }

    private fun wrapIfSensitive(
        definition: VariableDefinition,
        value: Any,
    ): Any =
        if (definition.sensitive && value !is SensitiveValue) {
            SensitiveValue.of(value as? String ?: value.toString())
        } else {
            value
        }

    companion object {
        /** 設計書§2.8の6種標準Resolverを規定の優先順位で組んだ既定のChain。 */
        fun standard(secretManagerAdapter: SecretManagerAdapter): VariableResolverChain =
            VariableResolverChain(
                listOf(
                    ExplicitParameterResolver(),
                    StaticResolver(),
                    UserResolver(),
                    WorkflowResolver(),
                    EnvironmentResolver(),
                    SecretResolver(secretManagerAdapter),
                ),
            )
    }
}
