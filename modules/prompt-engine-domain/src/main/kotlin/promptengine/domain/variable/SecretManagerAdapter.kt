package promptengine.domain.variable

import promptengine.domain.shared.SensitiveValue

/**
 * Secret Manager（外部システム）へのアクセスを抽象化するInterface（設計書§2.8「Secret Manager
 * 参照名のみDSLに記載」、ADR-0011）。実装は`prompt-engine-infrastructure`が持つ
 * （M1は環境変数バックエンド）。
 *
 * [getSecret] は[name]（`VariableDefinition.name`、Secret Manager上の参照名として使う）に
 * 対応する値が見つからなかった場合は`null`を返す（「未設定」という正常系の異常。
 * [SecretResolver]はこれを他のResolver同様の「未解決」として扱い、`required`ならば
 * [VariableUnresolvedException]に含める）。
 *
 * Secret Manager自体への到達性・認証エラー等のインフラ障害は`null`を返さず例外を投げる
 * こと。この例外は[VariableUnresolvedException]に畳み込まれず、そのまま呼び出し元へ
 * 伝播する（ADR-0011。設計書§13.3に定義された`VARIABLE_UNRESOLVED`は「変数固有の
 * 未解決」を表すコードであり、インフラ障害はそれとは別種の失敗であるため）。
 *
 * 実装は取得した値を[SensitiveValue]でラップした状態で返すこと。生の`String`が
 * `SecretManagerAdapter`の呼び出し元（`SecretResolver`）へ渡る前に必ずラップを終える
 * ことで、平文の秘密が上位層に一切露出しない構造にする。
 */
interface SecretManagerAdapter {
    fun getSecret(name: String): SensitiveValue?
}
