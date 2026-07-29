package promptengine.engine.resolver

import promptengine.domain.shared.PromptRequest
import promptengine.domain.variable.SecretManagerAdapter
import promptengine.domain.variable.VariableDefinition
import promptengine.domain.variable.VariableResolver
import promptengine.domain.variable.VariableResolverChain
import promptengine.domain.variable.VariableSource

/**
 * `source=SECRET`の変数を[SecretManagerAdapter]経由でSecret Managerから解決する
 * （設計書§2.8「Secret」、§5.3シーケンス）。
 *
 * [SecretManagerAdapter.getSecret]が`null`を返した場合（Secretが未設定）は、この
 * Resolverも`null`を返し、[VariableResolverChain]が他の未解決変数と同様に
 * `VARIABLE_UNRESOLVED`へ含める。[SecretManagerAdapter.getSecret]が例外を投げた場合
 * （Secret Manager自体への到達性・認証エラー等のインフラ障害）は、この
 * Resolverはその例外を捕捉せずそのまま伝播させる。`VARIABLE_UNRESOLVED`には
 * 混ぜない（ADR-0011）。
 *
 * 返す値は[SecretManagerAdapter]が既に[promptengine.domain.shared.SensitiveValue]で
 * ラップ済みのため、生の`String`がこのクラスの内部を一切通過しない。
 */
class SecretResolver(
    private val secretManagerAdapter: SecretManagerAdapter,
) : VariableResolver {
    override fun resolve(
        definition: VariableDefinition,
        request: PromptRequest,
    ): Any? {
        if (definition.source != VariableSource.SECRET) return null
        return secretManagerAdapter.getSecret(definition.name)
    }
}
