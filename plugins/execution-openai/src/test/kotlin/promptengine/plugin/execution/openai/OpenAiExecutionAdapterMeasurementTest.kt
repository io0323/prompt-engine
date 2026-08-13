package promptengine.plugin.execution.openai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.render.MessageRole
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderedMessage
import promptengine.domain.render.RenderedPrompt
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
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
 */
@EnabledIfEnvironmentVariable(named = "PE_OPENAI_API_KEY", matches = ".+")
class OpenAiExecutionAdapterMeasurementTest {
    @Test
    fun `本番相当サイズのfixtureで反復計測しレイテンシ_usage_コストを記録する`() {
        val apiKey = System.getenv("PE_OPENAI_API_KEY")
        val measureCount = (System.getenv("PE_OPENAI_MEASURE_COUNT") ?: DEFAULT_MEASURE_COUNT.toString()).toInt()
        val warmupCount = (System.getenv("PE_OPENAI_WARMUP_COUNT") ?: DEFAULT_WARMUP_COUNT.toString()).toInt()
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

    private fun percentile(
        sortedMs: List<Long>,
        p: Double,
    ): Long {
        val idx = (sortedMs.size * p).roundToLong().toInt().coerceIn(1, sortedMs.size) - 1
        return sortedMs[idx]
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
    }
}
