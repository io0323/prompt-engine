package promptengine.plugin.execution.openai

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount

/**
 * 実プロバイダAPIへの疎通確認（M2-1c、ADR-0030決定3）。
 *
 * 課金・外部要因による不安定性をCIの必須ゲートに持ち込まないため、環境変数
 * `PE_OPENAI_API_KEY`が設定されている場合のみ実行する。GradleのTag除外
 * （`excludeTags`、`RenderLoadSeeder`の`perf`タグと同じ手法）ではなくJUnit5の
 * [EnabledIfEnvironmentVariable]を使う理由: Tag除外はテスト自体を実行対象から除外し
 * JUnit XMLに一切現れないため`tests="0"`のまま"BUILD SUCCESSFUL"になる（silent green、
 * このプロジェクトで繰り返し潰してきた失敗パターン）。本アノテーションはテストを
 * 実行はするがJUnit5内部でskip扱いにするため、JUnit XMLに`skipped="1"`として残る。
 * CIの`test`ジョブはこれを検出して`::notice::`を出し、PRのChecksタブで
 * 「実行されなかった」事実自体を可視化する（.github/workflows/ci.yml）。
 *
 * 本テストは1回の最小リクエストのみ行う（接続性の確認が目的）。レイテンシ・usage・
 * コストの実測（複数回、統計的な把握が目的）は本テストのスコープ外——実プロバイダへの
 * 反復リクエストは課金額が無視できなくなるため、CIで実行され得る本テストには含めない。
 * 実測はユーザー自身が手元で実行する（`tools/perf/README.md`参照、ADR-0030決定3。
 * APIキーをエージェント/CIのプロセスに渡さない方針そのものが理由であり、
 * 経路を絞るというこのプロジェクトの秘密管理原則と一致させている）。
 */
@EnabledIfEnvironmentVariable(named = "PE_OPENAI_API_KEY", matches = ".+")
class OpenAiExecutionAdapterRealApiTest {
    @Test
    fun `実APIへの最小リクエストが成功する`() {
        val apiKey = System.getenv("PE_OPENAI_API_KEY")
        val adapter = OpenAiExecutionAdapter(apiKey = SensitiveValue.of(apiKey), model = MODEL_NAME)

        val response = adapter.execute(prompt(), policy())

        response.content.expose().shouldNotBeBlank()
        (response.usage.inputTokens.value > 0) shouldBe true
        (response.usage.outputTokens.value > 0) shouldBe true
        println(
            "real_api_smoke_test: model=$MODEL_NAME latencyMs=${response.latency.value} " +
                "inputTokens=${response.usage.inputTokens.value} outputTokens=${response.usage.outputTokens.value}",
        )
    }

    private fun prompt(): RenderedPrompt =
        RenderedPrompt(
            messages = listOf(RenderedMessage(MessageRole.USER, "Reply with exactly one word: pong")),
            outputFormat = OutputFormat.TEXT,
            tokenEstimate = TokenCount(10),
            renderHash = "real-api-smoke-test",
        )

    private fun policy(): ExecutionPolicy = ExecutionPolicy(timeoutMs = TIMEOUT_MS)

    companion object {
        private const val MODEL_NAME = "gpt-4o-mini"
        private const val TIMEOUT_MS = 15_000L
    }
}
