package promptengine.plugin.execution.openai

import promptengine.domain.execution.ExecutionErrorType
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import javax.net.ssl.SSLHandshakeException

/**
 * `java.net.http.HttpClient.send`が投げうる例外→[ExecutionErrorType]の分類（M2-1a、ADR-0029決定4）。
 *
 * 分類原則（ADR-0029決定4、ADR-0014決定7の「未送信と断定できる場合のみリトライ可」を実装に落としたもの）:
 * リクエストが**送信されていないと確実に言える**場合のみ[ExecutionErrorType.CONNECTION_FAILURE]/
 * [ExecutionErrorType.CONNECT_TIMEOUT]（リトライ可）とする。送信されたかどうか判別できない場合は、
 * 安全側で[ExecutionErrorType.UNKNOWN]（リトライ不可）に倒す。二重実行（二重課金）のリスクを、
 * リトライで得られる可用性より優先する。
 *
 * 分岐順序に関する注意（実測で発見、削除・並べ替え厳禁）:
 * [HttpConnectTimeoutException]は[HttpTimeoutException]のサブタイプである。このためこの2つの
 * 分岐の順序を入れ替える（[HttpTimeoutException]を先に判定する）と、接続タイムアウトが
 * 全て[ExecutionErrorType.READ_TIMEOUT]（リトライ不可）に誤分類される。これはADR-0014の
 * リトライ安全性の前提そのものを壊す。[OpenAiFailureClassifierTest]の
 * `接続タイムアウトはHttpTimeoutExceptionの分岐に落ちずCONNECT_TIMEOUTに分類される`が
 * この順序を直接固定する回帰テスト。
 */
internal object OpenAiFailureClassifier {
    fun classify(e: Throwable): ExecutionErrorType =
        when (e) {
            // 接続確立前のタイムアウト。未送信と断定できる。HttpTimeoutExceptionより先に判定必須。
            is HttpConnectTimeoutException -> ExecutionErrorType.CONNECT_TIMEOUT
            // 接続確立後、応答待機中のタイムアウト。先方で実行済み・課金済みの可能性を否定できない。
            is HttpTimeoutException -> ExecutionErrorType.READ_TIMEOUT
            // DNS解決失敗。TCP接続すら試みられていないため未送信と断定できる。
            is UnknownHostException -> ExecutionErrorType.CONNECTION_FAILURE
            // TCP接続確立自体の失敗（例: connection refused）。未送信と断定できる。
            is ConnectException -> ExecutionErrorType.CONNECTION_FAILURE
            // TLSハンドシェイク失敗。ハンドシェイク完了前であり、アプリケーション層データ
            // （HTTPリクエスト）は未送信と断定できる。
            is SSLHandshakeException -> ExecutionErrorType.CONNECTION_FAILURE
            // 上記以外のIOException（例: 再利用中の古いコネクションが書き込み後に切断される、
            // ハンドシェイク後のSSLException等）は、送信済みかどうか判別できない。
            // 安全側でリトライ不可として扱う（READ_TIMEOUTへ流用せず、意味的に正確な
            // UNKNOWNを使う。ADR-0014のUNKNOWN定義「分類不能。安全側に倒しリトライ不可」と一致）。
            is IOException -> ExecutionErrorType.UNKNOWN
            // 割り込み（JVMシャットダウン等）を含む、その他一切。送信済みかどうか判別できないため
            // 安全側でリトライ不可。
            else -> ExecutionErrorType.UNKNOWN
        }
}
