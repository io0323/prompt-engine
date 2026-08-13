package promptengine.plugin.execution.openai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.roundToLong

/**
 * 実プロバイダAPIに対するレイテンシ・usage・コストの反復計測（M2-1c、ADR-0030決定3）。
 *
 * [OpenAiExecutionAdapterRealApiTest]（CIでも実行されうる最小疎通確認、1リクエストのみ）とは
 * 目的が異なる: 本クラスは`tools/perf/README.md`の手順に従い**ユーザー自身が手元で明示的に**
 * `--tests`指定して実行する、複数回・課金を伴う計測専用クラスであり、CIからは一切参照しない
 * （APIキーをエージェント/CIのプロセスに渡さない方針、経路を絞るという秘密管理原則）。
 *
 * 実行回数・fixtureの根拠は`tools/perf/README.md`参照。結果はSystem.outへ人間可読な形で
 * 出力するのみで、レイテンシに対するassertion（合否判定）は行わない——実プロバイダの応答時間は
 * ネットワーク・プロバイダ側の負荷状況に左右され、固定閾値でのpass/fail判定は本フェーズの
 * 目的（「何が崩れるか」を実測して報告すること）に合わない。
 *
 * `PE_OPENAI_API_KEY`だけでなく`PE_OPENAI_RUN_MEASUREMENT=true`も必須にする（2条件のAND、
 * JUnit5の`@EnabledIfEnvironmentVariable`は繰り返し指定可能で複数条件はANDで評価される
 * ことを実測・公式ドキュメントで確認済み、CodeRabbitレビュー指摘）。理由:
 * [OpenAiExecutionAdapterRealApiTest]の接続性確認のためだけに`PE_OPENAI_API_KEY`を
 * 設定した状態で`--tests`を付けずに`./gradlew test`等を広く実行すると、本クラス
 * （既定25回の有償リクエスト）まで意図せず実行されてしまう。第2の明示フラグにより、
 * 反復計測は「本当にそれを意図した実行」でのみ発生するようにする。
 *
 * 正常系の反復計測（既定22回、[本番相当サイズのfixtureで反復計測しレイテンシ_usage_コストを記録する]）
 * に加え、エラー経路の実測（3回、[エラー経路を実測しExecutionErrorTypeへの分類が実物と一致するか確認する]、
 * Issue #92）を行う。契約テスト（`OpenAiExecutionAdapterContractTest`）はWireMockのスタブに対して
 * しか通っておらず、実プロバイダが本当にその形で応答するかは未確認だった——ADR-0014/ADR-0029の
 * 前提が崩れるとすればまずここである。
 */
@EnabledIfEnvironmentVariable(named = "PE_OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PE_OPENAI_RUN_MEASUREMENT", matches = "true")
class OpenAiExecutionAdapterMeasurementTest {
    @Test
    fun `本番相当サイズのfixtureで反復計測しレイテンシ_usage_コストを記録する`() {
        val apiKey = System.getenv("PE_OPENAI_API_KEY")
        val measureCount = parsePositiveInt("PE_OPENAI_MEASURE_COUNT", DEFAULT_MEASURE_COUNT)
        val warmupCount = parsePositiveInt("PE_OPENAI_WARMUP_COUNT", DEFAULT_WARMUP_COUNT)
        val adapter = OpenAiExecutionAdapter(apiKey = SensitiveValue.of(apiKey), model = MODEL_NAME)

        println("=== warmup ($warmupCount requests, not recorded) ===")
        repeat(warmupCount) { adapter.execute(productionScalePrompt(), policy()) }

        println("=== measurement ($measureCount requests, model=$MODEL_NAME) ===")
        val latenciesMs = mutableListOf<Long>()
        var totalInputTokens = 0L
        var totalOutputTokens = 0L
        repeat(measureCount) { i ->
            val response = adapter.execute(productionScalePrompt(), policy())
            latenciesMs += response.latency.value
            totalInputTokens += response.usage.inputTokens.value
            totalOutputTokens += response.usage.outputTokens.value
            println(
                "  [$i] latencyMs=${response.latency.value} inputTokens=${response.usage.inputTokens.value} " +
                    "outputTokens=${response.usage.outputTokens.value} " +
                    "contentPreview=${response.content.expose().take(PREVIEW_LENGTH).replace("\n", " ")}",
            )
        }

        val sorted = latenciesMs.sorted()
        val p50 = percentile(sorted, PERCENTILE_50)
        val p99 = percentile(sorted, PERCENTILE_99)
        println("=== RESULT ===")
        println("  model: $MODEL_NAME")
        println("  measured requests: $measureCount (warmup: $warmupCount, not recorded)")
        println("  latency p50 (ms): $p50")
        println("  latency p99 (ms): $p99")
        println("  latency min/max (ms): ${sorted.first()}/${sorted.last()}")
        println("  total inputTokens: $totalInputTokens (avg ${totalInputTokens / measureCount})")
        println("  total outputTokens: $totalOutputTokens (avg ${totalOutputTokens / measureCount})")
        println(
            "  NOTE: costPerTokenはModelProfileProperties（promptengine.model-profile.cost-per-token）" +
                "の設定値であり、本テストは算出しない。実コストはプロバイダの請求ダッシュボードで確認すること。",
        )
    }

    /**
     * エラー経路の実測（3リクエスト、Issue #92）。
     *
     * 正常系の反復計測だけでは、ADR-0014/ADR-0029のリトライ方針・分類ロジックが実際に
     * 前提とする「プロバイダの4xx応答の形」を検証できない——契約テスト（`OpenAiExecutionAdapterContractTest`）
     * はWireMockのスタブに対してしか通っておらず、実プロバイダが本当にその形（`error.code`等）で
     * 返すかは未確認のまま。3ケースとも1リクエストのみで、認証・モデル解決・入力検証のいずれかの
     * 段階で拒否される想定のため課金はほぼ発生しない（コンテキスト長超過はモデル実行前に拒否され、
     * 無効なキー・存在しないモデル名は認証/ルーティング段階で拒否される）。
     *
     * [OpenAiExecutionAdapter]を経由せず生の[HttpClient]でリクエストを送る。理由:
     * [ExecutionFailedException]は`errorType`と（一部ケースのみ）`cause`しか保持せず、生の
     * HTTPステータス・応答本文を呼出元へ返さない（ADR-0014決定9、秘密情報混入を避ける設計）。
     * 「実際に返ってきたHTTPステータス・応答本文」と「分類されたExecutionErrorType」を両方
     * 記録するには、1回のHTTPレスポンスを両方の用途に使う必要がある。[OpenAiResponseParser.parse]
     * （internal、同一モジュール）へ生の[HttpResponse]をそのまま渡すことで、
     * [OpenAiExecutionAdapter]が内部的に使うのと全く同じ分類ロジックを、生のステータス・本文と
     * 併せて記録できる（再実装せず実際のロジックを直接検証する）。
     */
    @Test
    fun `エラー経路を実測しExecutionErrorTypeへの分類が実物と一致するか確認する`() {
        val apiKey = System.getenv("PE_OPENAI_API_KEY")
        println("=== error path measurement (3 requests) ===")

        measureErrorPath("コンテキスト長超過", buildRawRequest(apiKey, MODEL_NAME, oversizedContent()))
        measureErrorPath("無効なAPIキー", buildRawRequest(INVALID_API_KEY, MODEL_NAME, "ping"))
        measureErrorPath("存在しないモデル名", buildRawRequest(apiKey, NONEXISTENT_MODEL_NAME, "ping"))
    }

    private fun measureErrorPath(
        label: String,
        httpRequest: HttpRequest,
    ) {
        val httpClient =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ERROR_PATH_CONNECT_TIMEOUT_SECONDS))
                .build()
        val httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        println("--- $label ---")
        println("  HTTP status: ${httpResponse.statusCode()}")
        println("  response body: ${httpResponse.body().take(RESPONSE_BODY_PREVIEW_LENGTH)}")

        // 想定外の成功・想定外の例外型は、このシナリオがエラー経路として機能していない
        // （＝実プロバイダの挙動が前提から崩れている）ことを意味するため、診断出力だけで
        // 済ませずテスト自体を失敗させる（CodeRabbitレビュー指摘）。どのExecutionErrorTypeに
        // 分類されるかは意図的に固定しない（それ自体が実測で確認したい対象のため）。
        val failure =
            runCatching { OpenAiResponseParser.parse(ObjectMapper(), httpResponse, LatencyMs(0)) }
                .exceptionOrNull()
        when (failure) {
            is ExecutionFailedException ->
                println(
                    "  classified ExecutionErrorType: ${failure.errorType}" +
                        " / cause: ${failure.cause?.javaClass?.simpleName ?: "none"}" +
                        (failure.cause?.message?.let { " ($it)" } ?: ""),
                )
            null -> throw AssertionError("$label: expected an error response but the request succeeded")
            else -> throw AssertionError("$label: expected ExecutionFailedException but got $failure", failure)
        }
    }

    private fun buildRawRequest(
        apiKey: String,
        model: String,
        content: String,
    ): HttpRequest {
        val mapper = ObjectMapper()
        val message =
            mapper.createObjectNode().apply {
                put("role", "user")
                put("content", content)
            }
        val messages = mapper.createArrayNode().apply { add(message) }
        val root =
            mapper.createObjectNode().apply {
                put("model", model)
                set<JsonNode>("messages", messages)
            }
        return HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofMillis(TIMEOUT_MS))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
            .build()
    }

    /**
     * 既知のどのモデルのコンテキスト長上限も確実に超える入力を生成する（実際の上限値を
     * 前提にしない。将来上限が拡張されても超過し続けるよう十分な余裕を持たせる）。
     * モデル実行前の入力検証で拒否される想定のため、この入力自体が生成コストを生むことはない。
     */
    private fun oversizedContent(): String = FILLER_LINE.repeat(OVERSIZED_CONTENT_REPEAT_COUNT)

    private fun percentile(
        sortedMs: List<Long>,
        p: Double,
    ): Long {
        val idx = (sortedMs.size * p).roundToLong().toInt().coerceIn(1, sortedMs.size) - 1
        return sortedMs[idx]
    }

    /**
     * 環境変数を正の整数として読む。0・負数・非数値は早期に明確なエラーとして拒否する
     * （CodeRabbitレビュー指摘: 未検証だと`measureCount=0`で空リストのpercentile計算や
     * ゼロ除算（平均トークン数算出）が不明瞭なエラーとして現れる）。
     */
    private fun parsePositiveInt(
        envVarName: String,
        default: Int,
    ): Int {
        val raw = System.getenv(envVarName) ?: return default
        val value = raw.toIntOrNull()
        require(value != null && value > 0) {
            "$envVarName must be a positive integer: '$raw'"
        }
        return value
    }

    /**
     * P11性能測定fixture（`tests/prompt-regression/fixtures/valid/
     * 04-production-scale-support-agent.prompt`）と同じ内容（長いsystemブロック+
     * 複数のfew-shot例）を、DSL解析を経ずに直接[RenderedMessage]として保持する。
     * このモジュールは`prompt-engine-core`（DSL/RenderEngine）へ依存できない
     * （ADR-0003規約6）ため、同じ「本番相当サイズ」であることを別モジュールへの依存なしに
     * 再現する。
     */
    private fun productionScalePrompt(): RenderedPrompt =
        RenderedPrompt(
            messages =
                listOf(
                    RenderedMessage(
                        MessageRole.SYSTEM,
                        """
                        あなたは「Prompt Engine」プラットフォームのカスタマーサポートを担当するAIエージェントです。
                        以下の方針に必ず従ってください。

                        ## 役割
                        - ユーザーからの問い合わせに対し、正確かつ簡潔に回答してください。
                        - 不明な点は推測で答えず、必要な追加情報をユーザーに質問してください。
                        - 回答は日本語で行い、専門用語を使う場合は簡単な説明を添えてください。

                        ## トーン
                        - 丁寧かつ親しみやすい敬語を使用してください。
                        - ユーザーを急かしたり、不安にさせたりする表現は避けてください。
                        - 謝罪が必要な場面では、原因の説明よりも先に共感を示してください。

                        ## 禁止事項
                        - 実在しない機能やAPIエンドポイントについて回答しないでください。
                        - 課金・返金に関する最終判断はサポートチームへエスカレーションし、自分では確約しないでください。
                        - ユーザーのAPIキーやパスワードなど、機密情報の値をそのまま復唱しないでください。

                        ## エスカレーション基準
                        - 障害・セキュリティに関する報告は即座に severity: high としてエスカレーションしてください。
                        - 課金トラブルは billing-team へ、技術的な不具合は platform-team へ引き継いでください。
                        - 3往復以上のやり取りで解決しない場合は、人間のオペレーターへの引き継ぎを提案してください。

                        ## 回答フォーマット
                        1. 要約（1-2文）
                        2. 詳細な説明または手順
                        3. 必要であれば次のアクション（エスカレーション・追加情報の依頼等）
                        """.trimIndent(),
                    ),
                    RenderedMessage(MessageRole.USER, "プロンプトを保存しようとすると「VALIDATION_FAILED」というエラーが出ます。原因は何でしょうか？"),
                    RenderedMessage(
                        MessageRole.ASSISTANT,
                        "要約: 入力したプロンプトが検証ルール（文字数上限またはプレースホルダ形式）に違反している可能性があります。\n\n" +
                            "詳細: 「VALIDATION_FAILED」は、保存前のバリデーションで、文字数上限・プレースホルダの記法・" +
                            "禁止ポリシーのいずれかに違反した場合に返されるエラーです。\n\n" +
                            "次のアクション: findingsの内容を確認し、該当箇所を修正のうえ再度保存をお試しください。",
                    ),
                    RenderedMessage(MessageRole.USER, "Versionをpublishしたのに、renderすると古い内容が返ってきます。"),
                    RenderedMessage(
                        MessageRole.ASSISTANT,
                        "要約: キャッシュの反映に一時的な遅延が発生している可能性があります。\n\n" +
                            "詳細: publish直後は、依存関係の解決やキャッシュの無効化に数秒かかる場合があります。\n\n" +
                            "次のアクション: 数分待っても解消しない場合はseverity: highとしてplatform-teamへエスカレーションします。",
                    ),
                    RenderedMessage(
                        MessageRole.USER,
                        "山田太郎です。チケット概要: ダッシュボードのグラフが表示されない現象について。ブラウザはChrome最新版。",
                    ),
                ),
            outputFormat = OutputFormat.TEXT,
            tokenEstimate = TokenCount(2000),
            renderHash = "real-api-measurement",
        )

    private fun policy(): ExecutionPolicy = ExecutionPolicy(timeoutMs = TIMEOUT_MS)

    companion object {
        private const val MODEL_NAME = "gpt-4o-mini"
        private const val TIMEOUT_MS = 30_000L
        private const val DEFAULT_MEASURE_COUNT = 20
        private const val DEFAULT_WARMUP_COUNT = 2
        private const val PREVIEW_LENGTH = 60
        private const val PERCENTILE_50 = 0.50
        private const val PERCENTILE_99 = 0.99

        // エラー経路測定（Issue #92）用の定数。
        private const val INVALID_API_KEY = "sk-invalid-test-key-0000000000000000000000000000"
        private const val NONEXISTENT_MODEL_NAME = "gpt-this-model-does-not-exist-pe-m2-1c"
        private const val FILLER_LINE = "This is filler text used only to intentionally exceed the context window. "
        private const val OVERSIZED_CONTENT_REPEAT_COUNT = 50_000
        private const val ERROR_PATH_CONNECT_TIMEOUT_SECONDS = 10L
        private const val RESPONSE_BODY_PREVIEW_LENGTH = 500
    }
}
