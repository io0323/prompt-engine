package promptengine.plugin.execution.openai

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount

private const val SECRET_API_KEY = "sk-test-do-not-leak-1234567890"

/**
 * APIキーが例外メッセージ・`toString()`・スタックトレースへ一切現れないことを検証する
 * （M2-1a、CLAUDE.md「Secret / sensitive=trueの変数値は絶対に出力しない」）。
 *
 * HTTPヘッダとして実際にWireへ送信されること自体は正しい動作であり秘匿対象ではない
 * （[APIキーがAuthorizationヘッダとして送信される]で肯定的に確認する）。ここで防ぐべきは
 * 「ヘッダ以外の経路（ログ・例外・toString）への漏洩」である。
 */
class OpenAiExecutionAdapterAuthSecrecyTest {
    @Test
    fun `APIキーがAuthorizationヘッダとして送信される`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"choices":[{"message":{"role":"assistant","content":"ok"}}],""" +
                            """"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                    ),
                ),
        )

        adapter().execute(prompt(), policy())

        wm.verify(
            postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer $SECRET_API_KEY")),
        )
    }

    @Test
    fun `アダプタのtoStringにAPIキーは現れない`() {
        adapter().toString() shouldNotContain SECRET_API_KEY
    }

    @Test
    fun `サーバエラー時の例外メッセージにAPIキーは現れない`() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(500)))

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }

        e.message.orEmpty() shouldNotContain SECRET_API_KEY
        e.toString() shouldNotContain SECRET_API_KEY
        e.stackTraceToString() shouldNotContain SECRET_API_KEY
    }

    @Test
    fun `不正なJSON応答時の例外メッセージ・原因メッセージにAPIキーは現れない`() {
        wm.stubFor(
            post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody("not json, and definitely not $SECRET_API_KEY")),
        )

        val e = shouldThrow<ExecutionFailedException> { adapter().execute(prompt(), policy()) }

        e.stackTraceToString() shouldNotContain SECRET_API_KEY
        e.cause?.message.orEmpty() shouldNotContain SECRET_API_KEY
    }

    private fun adapter(): OpenAiExecutionAdapter =
        OpenAiExecutionAdapter(
            apiKey = SensitiveValue.of(SECRET_API_KEY),
            model = "gpt-4o-mini",
            baseUrl = "${wm.baseUrl()}/v1",
        )

    private fun prompt(): RenderedPrompt =
        RenderedPrompt(
            messages = listOf(RenderedMessage(MessageRole.USER, "hi")),
            outputFormat = OutputFormat.TEXT,
            tokenEstimate = TokenCount(1),
            renderHash = "hash",
        )

    private fun policy(): ExecutionPolicy = ExecutionPolicy(timeoutMs = DEFAULT_TEST_TIMEOUT_MS)

    companion object {
        // OpenAiExecutionAdapterの既定connectTimeoutMs（5000L）より必ず大きくする
        // （execute内のrequire(policy.timeoutMs > connectTimeoutMs)を満たす必要があるため）。
        private const val DEFAULT_TEST_TIMEOUT_MS = 6_000L

        @JvmField
        @RegisterExtension
        val wm: WireMockExtension = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build()
    }
}
