package promptengine.infrastructure.adapter.secret

import promptengine.domain.shared.SensitiveValue
import promptengine.domain.variable.SecretManagerAdapter

/**
 * [SecretManagerAdapter]のM1実装（環境変数バックエンド、設計書§1.4・ADR-0011）。
 *
 * `VariableDefinition.name` を [envVarName] で環境変数名に変換し、[environment] から
 * 値を引く。将来Secret Manager（Vault等）に差し替える際は本クラスを別実装に置換する
 * だけでよく、`SecretResolver`側の変更は不要（設計書§16「拡張ポイント」）。
 *
 * [environment] は既定で `System.getenv()` を使うが、テストからは任意のMapを注入できる。
 *
 * 値が見つからない場合は`null`を返す（「未設定」。[promptengine.domain.variable.SecretResolver]が
 * 他の未解決変数と同様に扱う）。環境変数読み取り自体は失敗しうる操作ではないため、
 * このM1実装が例外を投げることは通常ない。実際にSecret Managerへ差し替わった際は、
 * 到達性・認証エラー等のインフラ障害を[getSecret]から例外として投げ、`null`（未設定）とは
 * 区別すること（ADR-0011）。
 */
class EnvironmentSecretManagerAdapter(
    private val environment: Map<String, String> = System.getenv(),
    private val envVarPrefix: String = DEFAULT_PREFIX,
) : SecretManagerAdapter {
    override fun getSecret(name: String): SensitiveValue? = environment[envVarName(name)]?.let { SensitiveValue.of(it) }

    private fun envVarName(name: String): String = envVarPrefix + name.uppercase()

    companion object {
        const val DEFAULT_PREFIX = "PE_SECRET_"
    }
}
