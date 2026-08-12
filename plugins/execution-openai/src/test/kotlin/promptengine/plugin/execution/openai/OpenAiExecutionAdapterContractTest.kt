package promptengine.plugin.execution.openai

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import promptengine.domain.execution.ExecutionErrorType
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount

/**
 * [OpenAiExecutionAdapter]をWireMockで疑似APIサーバに対して実際にHTTP通信させ、
 * 応答→[promptengine.domain.execution.RawResponse]/[ExecutionFailedException]変換を検証する
 * （M2-1a、ADR-0029）。ネットワーク層例外の型ごとの分類ロジックは[OpenAiFailureClassifierTest]
 * （実通信なし、決定的）が担う。ここでは「実際のJava HttpClientが各シナリオで本当にどの例外を
 * 投げるか」（WireMockのFault機能でのみ再現できるもの）を検証する。
 */
class OpenAiExecutionAdapterContractTest {
    @Test
    fun `成功応答はcontent_usage_latencyを持つRawResponseへ変換される`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[{"message":{"role":"assistant","content":"hello"}}],""" +
                            """"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                    ),
                ),
        )

        val response = adapter().execute(prompt(), policy())

        response.content.expose() shouldBe "hello"
        response.usage.inputTokens shouldBe TokenCount(10)
        response.usage.outputTokens shouldBe TokenCount(5)
    }

    @Test
    fun `system_assistantロールを含むメッセージ列は対応するroleでリクエストされる`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[{"message":{"role":"assistant","content":"ok"}}],""" +
                            """"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                    ),
                ),
        )

        val multiRolePrompt =
            RenderedPrompt(
                messages =
                    listOf(
                        RenderedMessage(MessageRole.SYSTEM, "you are a helper"),
                        RenderedMessage(MessageRole.USER, "hi"),
                        RenderedMessage(MessageRole.ASSISTANT, "previous reply"),
                    ),
                outputFormat = OutputFormat.TEXT,
                tokenEstimate = TokenCount(1),
                renderHash = "hash",
            )

        adapter().execute(multiRolePrompt, policy())

        wm.verify(
            postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing(""""role":"system""""))
                .withRequestBody(containing(""""role":"assistant""""))
                .withRequestBody(containing(""""role":"user"""")),
        )
    }

    @Test
    fun `429はRATE_LIMITEDに分類される`() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(429)))

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.RATE_LIMITED
    }

    @Test
    fun `5xxはSERVER_ERRORに分類される`() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(503)))

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.SERVER_ERROR
    }

    @Test
    fun `429を除く4xxはCLIENT_ERRORに分類される`() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(401)))

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.CLIENT_ERROR
    }

    @Test
    fun `429_4xx_5xxのいずれでもないステータスはUNKNOWNに分類される`() {
        // 300番台等、429/4xx/5xxのいずれにも該当しない想定外のステータス。
        wm.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(302)))

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
    }

    @Test
    fun `contentが文字列でないHTTP200はUNKNOWNとして扱われる`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[{"message":{"role":"assistant","content":123}}],""" +
                            """"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                    ),
                ),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
        (e.cause is OpenAiMissingContentException) shouldBe true
    }

    @Test
    fun `usageがnullのHTTP200はUNKNOWNとして扱われる`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[{"message":{"role":"assistant","content":"hello"}}],"usage":null}""",
                    ),
                ),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
        (e.cause is OpenAiUsageMissingException) shouldBe true
    }

    @Test
    fun `prompt_tokensが数値でないHTTP200はUNKNOWNとして扱われる`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[{"message":{"role":"assistant","content":"hello"}}],""" +
                            """"usage":{"prompt_tokens":"many","completion_tokens":1}}""",
                    ),
                ),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
        (e.cause is OpenAiUsageMissingException) shouldBe true
    }

    @Test
    fun `completion_tokensが数値でないHTTP200はUNKNOWNとして扱われる`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[{"message":{"role":"assistant","content":"hello"}}],""" +
                            """"usage":{"prompt_tokens":1,"completion_tokens":"many"}}""",
                    ),
                ),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
        (e.cause is OpenAiUsageMissingException) shouldBe true
    }

    @Test
    fun `不正なJSON応答はUNKNOWNに分類される`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody("not json")),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
        (e.cause is OpenAiMalformedResponseException) shouldBe true
    }

    @Test
    fun `usageフィールドが欠落したHTTP200はゼロ埋めせずUNKNOWNとして扱われる`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""",
                    ),
                ),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
        (e.cause is OpenAiUsageMissingException) shouldBe true
    }

    @Test
    fun `choices が空のHTTP200はUNKNOWNとして扱われる`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                    ),
                ),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
        (e.cause is OpenAiMissingContentException) shouldBe true
    }

    @Test
    fun `応答待機タイムアウトはREAD_TIMEOUTに分類される`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(READ_TIMEOUT_DELAY_MS)),
        )

        val e =
            shouldThrow<ExecutionFailedException> {
                adapter().execute(prompt(), policy(timeoutMs = SHORT_TIMEOUT_MS))
            }
        e.errorType shouldBe ExecutionErrorType.READ_TIMEOUT
    }

    @Test
    fun `書き込み後のコネクションリセットは送信済みか判別できずUNKNOWNとして扱われる`() {
        // 再利用中の古いコネクションが応答直前に切断されるケースの実測（ADR-0029決定4）。
        // OpenAiFailureClassifierの汎用IOException分岐（is IOException -> UNKNOWN）に
        // 実際に落ちることを、本物のJava HttpClient例外で確認する。
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.UNKNOWN
    }

    @Test
    fun `TLSハンドシェイク失敗はCONNECTION_FAILUREに分類される`() {
        // WireMockの既定自己署名証明書はJVM既定のTrustStoreに信頼されないため、
        // カスタムTrustManagerを与えないHttpClientでの接続はハンドシェイクで失敗する。
        val httpsAdapter =
            OpenAiExecutionAdapter(
                apiKey = SensitiveValue.of("test-key"),
                model = "gpt-4o-mini",
                baseUrl = "https://localhost:${wm.httpsPort}/v1",
            )

        val e = shouldThrow<ExecutionFailedException> { httpsAdapter.execute(prompt(), policy()) }
        e.errorType shouldBe ExecutionErrorType.CONNECTION_FAILURE
    }

    private fun adapter(): OpenAiExecutionAdapter =
        OpenAiExecutionAdapter(
            apiKey = SensitiveValue.of("test-key"),
            model = "gpt-4o-mini",
            // WireMockServer.baseUrl()はHTTPSが有効な場合httpsを返してしまう（dynamicHttpsPort()を
            // 併用しているため）。HTTP専用の契約テストではgetRuntimeInfo().httpBaseUrlを明示する。
            // スタブは"/v1/chat/completions"に登録しているため、baseUrlにも"/v1"を含める
            // （OpenAiExecutionAdapterはbaseUrlへ"/chat/completions"のみを追記する契約）。
            baseUrl = "${wm.runtimeInfo.httpBaseUrl}/v1",
        )

    private fun prompt(): RenderedPrompt =
        RenderedPrompt(
            messages = listOf(RenderedMessage(MessageRole.USER, "hi")),
            outputFormat = OutputFormat.TEXT,
            tokenEstimate = TokenCount(1),
            renderHash = "hash",
        )

    private fun policy(timeoutMs: Long = DEFAULT_TEST_TIMEOUT_MS): ExecutionPolicy =
        ExecutionPolicy(timeoutMs = timeoutMs)

    companion object {
        private const val DEFAULT_TEST_TIMEOUT_MS = 5_000L
        private const val SHORT_TIMEOUT_MS = 300L
        private const val READ_TIMEOUT_DELAY_MS = 2_000

        @JvmField
        @RegisterExtension
        val wm: WireMockExtension =
            WireMockExtension.newInstance()
                .options(wireMockConfig().dynamicPort().dynamicHttpsPort())
                .build()
    }
}
