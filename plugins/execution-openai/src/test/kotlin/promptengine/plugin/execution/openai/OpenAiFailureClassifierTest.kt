package promptengine.plugin.execution.openai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.execution.ExecutionErrorType
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * [OpenAiFailureClassifier]の分類を、実ネットワーク通信を一切介さず例外インスタンスを直接構築して
 * 検証する（M2-1a、ADR-0029決定4）。
 *
 * 意図的にWireMockを使わない: [HttpConnectTimeoutException]の再現には実際に接続不能なホストへの
 * 到達を待つ必要があり、CI環境のネットワーク挙動（到達不能アドレスに対する応答の速さ）に依存して
 * 不安定・低速になる。分類ロジック自体は純粋関数であるため、例外の「型」だけを直接与えれば
 * 決定的に検証できる（[OpenAiExecutionAdapterContractTest]はWireMockで実際のHTTPレイヤの挙動
 * （応答遅延によるREAD_TIMEOUT・接続リセット等）を検証する住み分け）。
 */
class OpenAiFailureClassifierTest {
    @Test
    fun `接続タイムアウトはHttpTimeoutExceptionの分岐に落ちずCONNECT_TIMEOUTに分類される`() {
        // HttpConnectTimeoutExceptionはHttpTimeoutExceptionのサブタイプ。分岐順序を誤ると
        // このケースがREAD_TIMEOUTに誤分類される（このテストが唯一その回帰を検知する）。
        val e: HttpTimeoutException = HttpConnectTimeoutException("connect timed out")
        OpenAiFailureClassifier.classify(e) shouldBe ExecutionErrorType.CONNECT_TIMEOUT
    }

    @Test
    fun `応答待機タイムアウトはREAD_TIMEOUTに分類される`() {
        val e = HttpTimeoutException("request timed out")
        OpenAiFailureClassifier.classify(e) shouldBe ExecutionErrorType.READ_TIMEOUT
    }

    @Test
    fun `DNS解決失敗はCONNECTION_FAILUREに分類される`() {
        val e = UnknownHostException("api.openai.invalid")
        OpenAiFailureClassifier.classify(e) shouldBe ExecutionErrorType.CONNECTION_FAILURE
    }

    @Test
    fun `接続拒否はCONNECTION_FAILUREに分類される`() {
        val e = ConnectException("Connection refused")
        OpenAiFailureClassifier.classify(e) shouldBe ExecutionErrorType.CONNECTION_FAILURE
    }

    @Test
    fun `TLSハンドシェイク失敗はCONNECTION_FAILUREに分類される`() {
        val e = SSLHandshakeException("handshake failed")
        OpenAiFailureClassifier.classify(e) shouldBe ExecutionErrorType.CONNECTION_FAILURE
    }

    @Test
    fun `ハンドシェイク後のSSLExceptionは送信済みか判別できないためUNKNOWNに分類される`() {
        // SSLHandshakeExceptionではない汎用SSLException（例: 通信中のプロトコルエラー）は
        // ハンドシェイク完了後に起きうる。データが既に送信された可能性を否定できない。
        val e = SSLException("connection reset during data transfer")
        OpenAiFailureClassifier.classify(e) shouldBe ExecutionErrorType.UNKNOWN
    }

    @Test
    fun `再利用済みコネクションの切断など判別不能なIOExceptionはUNKNOWNに分類される`() {
        val e = IOException("Connection reset")
        OpenAiFailureClassifier.classify(e) shouldBe ExecutionErrorType.UNKNOWN
    }

    @Test
    fun `割り込みはUNKNOWNに分類される`() {
        OpenAiFailureClassifier.classify(InterruptedException()) shouldBe ExecutionErrorType.UNKNOWN
    }

    @Test
    fun `分類不能なその他の例外はUNKNOWNに分類される`() {
        OpenAiFailureClassifier.classify(RuntimeException("unexpected")) shouldBe ExecutionErrorType.UNKNOWN
    }
}
