package promptengine.plugin.execution.openai

/**
 * OpenAI応答（HTTP 200）に`usage`フィールドが欠落・不正だった場合に投げる（M2-1a、ADR-0029決定5）。
 *
 * [Usage][promptengine.domain.execution.Usage]は必須フィールドであり「推定値」や「欠落フラグ」を
 * 持たない（domain変更なしの制約、CLAUDE.md「作業の進め方」）。そのため`0`トークンで
 * 黙って埋めることはコスト・使用量分析を静かに破損させる。[OpenAiExecutionAdapter]はこれを
 * [promptengine.domain.execution.ExecutionErrorType.UNKNOWN]（リトライ不可、二重課金を避ける）
 * として[promptengine.domain.execution.ExecutionFailedException]の`cause`に本例外を積んで投げる。
 *
 * メッセージ文字列を、接続エラー等の「本物のプロバイダ障害」と一目で区別できる固定文言にすることで、
 * この例外がログのcauseチェーンに現れた際に運用上識別できるようにする（本Pluginは
 * domain/plugin-apiの公開型以外のモジュールに依存できないため、専用メトリクスは発行できない。
 * `cause`の型・メッセージが唯一の識別子となる。ADR-0029決定5「副作用の抑制」参照）。
 */
internal class OpenAiUsageMissingException :
    RuntimeException(
        "openai_usage_missing: HTTP 200 response had no valid 'usage' field; " +
            "refusing to default token counts to zero (would silently corrupt cost/usage analytics)",
    )

/** OpenAI応答（HTTP 200）に`choices[0].message.content`が見つからなかった場合に投げる（M2-1a）。 */
internal class OpenAiMissingContentException :
    RuntimeException(
        "openai_missing_content: HTTP 200 response had no text content at choices[0].message.content",
    )

/**
 * OpenAI応答ボディがJSONとして構文解析できなかった場合に投げる（M2-1a）。
 *
 * [cause]（Jacksonの例外）のメッセージは応答ボディの一部を含みうるため、本例外自身の
 * メッセージには転記しない（`JsonOutputFormatter.parseTree`と同じ方針、ADR-0014決定9）。
 */
internal class OpenAiMalformedResponseException(cause: Throwable) :
    RuntimeException("openai_malformed_response: response body was not valid JSON", cause)
