package promptengine.plugin.execution.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.execution.RawResponse
import promptengine.domain.render.MessageRole
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SensitiveValue
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenAI Chat Completions APIへ直接接続する[ExecutionAdapter]実装（M2-1a、ADR-0029）。
 *
 * ## 位置づけ（削除可能性を最優先した暫定実装）
 * 設計上PEはAI Providerに直接触れず、実行はAPAP（AI Provider Abstraction Platform）へ
 * 委譲する構成である（設計書§16拡張ポイント#11、[ExecutionAdapter]のKDoc）。しかし本セッション
 * 時点でAPAPは実在しない。#31（実APAP接続）着手までの間、Pipeline全体をFakeではない実プロバイダで
 * 検証できるようにするための**暫定**実装として、本クラスを`prompt-engine-infrastructure`ではなく
 * `plugins/execution-openai`（Plugin実装、ADR-0003命名規則）に置く。理由は「拡張ポイントとの
 * 一貫性」ではなく「削除可能性」: 実APAP接続が実現した時点で、このディレクトリを`git rm -r`
 * 一発で消せる境界に置くことを優先した（ADR-0029決定1）。`prompt-engine-bootstrap`のDI配線
 * （`ExecutionConfig`）は本PR（M2-1a）では変更しない。実接続はM2-1cで行う。
 *
 * ## プロバイダ固有知識の封じ込め
 * "openai"という文字列・OpenAI固有のHTTP形状（エンドポイント・認証ヘッダ・JSONスキーマ）は
 * 本モジュール（`promptengine.plugin.execution.openai`）の外へ一切漏らさない。機械的な
 * 検証は`prompt-engine-bootstrap`の`ProviderNameContainmentTest`が行う（ADR-0029決定2）。
 *
 * ## タイムアウトの区別（ADR-0014のリトライ方針の前提）
 * [ExecutionErrorType.CONNECT_TIMEOUT]と[ExecutionErrorType.READ_TIMEOUT]を実装上区別できることは
 * 実測により確認済み（ADR-0029決定3）。[httpClient]に明示的な`connectTimeout`
 * （既定[DEFAULT_CONNECT_TIMEOUT_MS]）を設定しない場合、接続確立が遅延するケースがOS依存の
 * 長いタイムアウトまで`HttpTimeoutException`（[ExecutionErrorType.READ_TIMEOUT]、リトライ不可）
 * として観測されてしまい、本来リトライ可能な接続タイムアウトを取りこぼす。したがって
 * `connectTimeout`は[policy]の`timeoutMs`より必ず短い値を明示的に設定する。
 *
 * ## 例外分類
 * ネットワーク層例外の分類は[OpenAiFailureClassifier]に集約する（分岐順序の落とし穴を
 * 1箇所に閉じ込めるため）。HTTP応答の解釈（ステータス層の分類・content/usage欠落の扱い）は
 * [OpenAiResponseParser]が行う。
 *
 * @param apiKey OpenAI APIキー。ログ・例外メッセージに生値が現れないよう[SensitiveValue]で保持する。
 * @param model 使用するモデル名（例: `"gpt-4o-mini"`）。[promptengine.domain.optimization.ModelProfile]
 *   経由の抽象化はM2の実APAP接続時の課題であり、本暫定実装ではコンストラクタ引数の生文字列で扱う。
 * @param baseUrl APIのベースURL。契約テスト（WireMock）から差し替えられるよう既定値を持たせる。
 * @param connectTimeoutMs [httpClient]既定インスタンスの接続タイムアウト。[httpClient]を明示的に
 *   渡した場合はこの値は使われない。
 * @param httpClient 明示的に渡さない場合、[connectTimeoutMs]を`connectTimeout`に設定した
 *   インスタンスを構築する。
 */
class OpenAiExecutionAdapter(
    private val apiKey: SensitiveValue,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : ExecutionAdapter {
    override fun execute(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): RawResponse {
        val httpRequest = buildHttpRequest(prompt, policy)
        val start = System.nanoTime()
        val httpResponse =
            try {
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ExecutionFailedException(OpenAiFailureClassifier.classify(e), retryCount = 0, cause = e)
            } catch (e: IOException) {
                throw ExecutionFailedException(OpenAiFailureClassifier.classify(e), retryCount = 0, cause = e)
            }
        val latency = LatencyMs((System.nanoTime() - start) / NANOS_PER_MILLI)
        return OpenAiResponseParser.parse(objectMapper, httpResponse, latency)
    }

    private fun buildHttpRequest(
        prompt: RenderedPrompt,
        policy: ExecutionPolicy,
    ): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            // 応答待機（接続確立後）のタイムアウト。HttpClientのconnectTimeoutとは独立して
            // 効く（クラスKDoc「タイムアウトの区別」参照）。
            .timeout(Duration.ofMillis(policy.timeoutMs))
            .header("Authorization", "Bearer ${apiKey.expose()}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt)))
            .build()

    private fun buildRequestBody(prompt: RenderedPrompt): String {
        val messages = objectMapper.createArrayNode()
        prompt.messages.forEach { message ->
            val node = objectMapper.createObjectNode()
            node.put("role", roleToOpenAi(message.role))
            node.put("content", message.content)
            messages.add(node)
        }
        val root = objectMapper.createObjectNode()
        root.put("model", model)
        root.set<JsonNode>("messages", messages)
        return objectMapper.writeValueAsString(root)
    }

    private fun roleToOpenAi(role: MessageRole): String =
        when (role) {
            MessageRole.SYSTEM -> "system"
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            // M1のRenderEngineにはBlockRole側の対応が無く構造上到達不能（将来のTool結果
            // リプレイ用に用意されたロール、MessageRoleのKDoc参照）。構造的な対応のみ用意する。
            MessageRole.TOOL -> "tool"
        }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
