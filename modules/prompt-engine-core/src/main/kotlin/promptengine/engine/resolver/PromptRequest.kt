package promptengine.engine.resolver

/**
 * Variable/Context解決に必要な呼出時入力（設計書§3.4 `PromptRequest`）。
 *
 * 設計書は`PromptRequest`をInterfaceのパラメータ型として参照するのみでフィールドを
 * 定義していない（P1〜P3cは authoring・composition が対象でrequestという概念自体が
 * 未登場だったため）。P4で初めて必要になる最小形として、各Resolverが読むバッキング
 * ストアをまとめて持つ。
 *
 * - [explicitParameters] は呼出パラメータ（source無関係にsourceによる上書きより
 *   常に最優先、設計書§2.8）
 * - [userVariables] / [workflowVariables] / [environmentVariables] はそれぞれ
 *   `source=USER` / `WORKFLOW` / `ENVIRONMENT` の変数を解決するバッキングストア
 *   （呼出元・AACP・デプロイ設定が供給する値、いずれも変数名がキー）
 * - [contextData] はContext解決（§2.7の7スコープ）の生入力。スコープ名（例: "user"）を
 *   キーとし、値は各スコープのpath→値のMap
 *
 * Secret変数（`source=SECRET`）はこのRequestを経由せず、
 * [promptengine.domain.variable.SecretManagerAdapter] 経由で解決する。
 */
data class PromptRequest(
    val explicitParameters: Map<String, Any> = emptyMap(),
    val userVariables: Map<String, Any> = emptyMap(),
    val workflowVariables: Map<String, Any> = emptyMap(),
    val environmentVariables: Map<String, Any> = emptyMap(),
    val contextData: Map<String, Map<String, Any>> = emptyMap(),
)
