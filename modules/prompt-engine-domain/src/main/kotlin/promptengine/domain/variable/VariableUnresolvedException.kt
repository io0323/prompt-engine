package promptengine.domain.variable

/**
 * [VariableResolverChain]が`required=true`の変数を1件以上解決できなかった
 * （設計書§13.3 `VARIABLE_UNRESOLVED`、§2.8・§5.3シーケンス）。
 *
 * §5.3「未解決の変数名を1つ目で止めずに全て列挙して返すこと」の通り、
 * [missingNames] は解決ループ全体で見つかった未解決の必須変数名すべてを保持する。
 *
 * Secret変数（`source=SECRET`）について、[promptengine.domain.variable.SecretManagerAdapter]が
 * 単に値を持たなかった場合（未設定）はこの例外の[missingNames]に含める。一方
 * Secret Manager自体への到達性・認証エラー等のインフラ障害はこの例外に混ぜず、
 * 例外をそのまま伝播させる（ADR-0011）。HTTPコードへの写像はP9（REST API）で決定する。
 */
class VariableUnresolvedException(val missingNames: List<String>) :
    RuntimeException(
        "VARIABLE_UNRESOLVED: required variable(s) not resolved: ${missingNames.joinToString(", ")}",
    ) {
    init {
        require(missingNames.isNotEmpty()) { "missingNames must not be empty" }
    }
}
