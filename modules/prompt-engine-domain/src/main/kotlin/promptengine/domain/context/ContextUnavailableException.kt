package promptengine.domain.context

/**
 * [ContextResolverImpl]が`required`宣言されたContextスコープ・pathを1件以上
 * 解決できなかった（設計書§13.3 `CONTEXT_UNAVAILABLE`、§2.7・§5.4シーケンス）。
 *
 * [VariableUnresolvedException]と同様、最初に見つかった1件で止めず、解決ループ全体で
 * 見つかった未解決の必須スコープ記述子すべてを[missingRequirements]に集める。
 *
 * [missingRequirements] は呼出元が渡した[List]をそのまま保持せず不変コピーを取る。
 */
class ContextUnavailableException(missingRequirements: List<String>) :
    RuntimeException(
        "CONTEXT_UNAVAILABLE: required context requirement(s) not resolved: " +
            missingRequirements.joinToString(", "),
    ) {
    val missingRequirements: List<String> = missingRequirements.toList()

    init {
        require(missingRequirements.isNotEmpty()) { "missingRequirements must not be empty" }
    }
}
