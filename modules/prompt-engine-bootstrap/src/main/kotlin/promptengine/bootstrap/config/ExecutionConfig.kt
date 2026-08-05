package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import promptengine.application.pipeline.PipelineRequestFactory
import promptengine.domain.execution.ExecutionAdapter
import promptengine.domain.execution.ExecutionEngine
import promptengine.domain.execution.Usage
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.TokenCount
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.engine.execution.ExecutionCoordinator
import promptengine.engine.execution.RetryingExecutionAdapter
import promptengine.plugin.execution.fake.FakeExecutionAdapter
import promptengine.plugin.execution.fake.FakeExecutionScenario
import java.math.BigDecimal

/**
 * Execution段のDI配線と、`prompt-engine-application`のUseCaseが要求する[ModelProfile]既定値・
 * [PipelineRequestFactory]の配線（P9c、[PluginEngineConfig]のKDoc参照）。
 *
 * [executionAdapter]は現時点で唯一の[ExecutionAdapter]実装である[FakeExecutionAdapter]を
 * 使う（実APAPアダプタはM2）。`FakeExecutionAdapter`自身がactiveProfilesに`"production"`が
 * 含まれる場合に起動時エラーとするため（P9cで追加）、このアプリケーションは現状
 * `production`プロファイルでは起動できない。
 */
@Configuration
class ExecutionConfig {
    /**
     * [FakeExecutionAdapter]の既定シナリオ。実際のAPAP接続が無いM1では、Full-executionモードは
     * 常にこの固定応答を返す（テキストのエコー相当）。実APAPアダプタ導入（M2）まではこの制約を
     * 受け入れる（`production`プロファイルでは起動時エラーになるため、この制約が本番へ
     * 波及することはない）。
     *
     * `activeProfiles`は`@Value("#{environment.activeProfiles}")`ではなく[Environment]の
     * 直接注入で取得する。前者はアクティブプロファイルを1つも指定せずに起動した場合（既定の
     * 起動経路）に`null`へ解決され、Kotlinの非null引数チェックで`NullPointerException`と
     * なりコンテキスト起動自体が失敗する不具合があった（9c、初回起動確認で発覚。
     * [Environment]は`ApplicationContext`が必ず提供する解決可能な依存であり型ベース注入なら
     * この問題が起きない）。
     */
    @Bean
    fun executionAdapter(environment: Environment): ExecutionAdapter {
        val fake =
            FakeExecutionAdapter(
                scenario = DEFAULT_FAKE_SCENARIO,
                activeProfiles = environment.activeProfiles.toSet(),
            )
        return RetryingExecutionAdapter(fake)
    }

    @Bean
    fun executionEngine(
        executionAdapter: ExecutionAdapter,
        outputFormatters: Map<OutputFormat, OutputFormatter>,
        tokenizerPlugin: TokenizerPlugin,
    ): ExecutionEngine = ExecutionCoordinator(executionAdapter, outputFormatters, tokenizerPlugin)

    /**
     * `modelProfile`（設計書§13.2「APAP登録プロファイル参照名」、例:`"gpt-class-large"`）が
     * 指す実体を解決するレジストリは、`outputSchemaRef`（ADR-0022）と同様、実APAPアダプタ
     * （M2）と一体で設計すべき永続化対象であり、M1で新設しない。[FakeExecutionAdapter]自体が
     * どのモデルを指定しても同一の固定応答しか返さない（`modelProfile`の値によって挙動が
     * 変わらない）ため、`prompt-engine-interface`は要求されたプロファイル名の非空検証のみ行い、
     * 実際の[ModelProfile]にはこの1つの既定値を使う（Stage 7 Optimization/Stage 9 Execution
     * が動作するために構造上必要な値であり、M1時点では中身の妥当性がクライアントの指定に
     * 依存しない）。
     */
    @Bean
    fun defaultModelProfile(): ModelProfile =
        ModelProfile(
            maxContextTokens = TokenCount(DEFAULT_MAX_CONTEXT_TOKENS),
            tokenizerId = "approx",
            costPerToken = Cost(DEFAULT_COST_PER_TOKEN),
        )

    @Bean
    fun pipelineRequestFactory(defaultModelProfile: ModelProfile): PipelineRequestFactory =
        PipelineRequestFactory(defaultModelProfile)

    companion object {
        private const val DEFAULT_MAX_CONTEXT_TOKENS = 8_000
        private val DEFAULT_COST_PER_TOKEN = BigDecimal("0.000001")
        private val DEFAULT_FAKE_SCENARIO =
            FakeExecutionScenario.Success(
                content = "{}",
                usage = Usage(TokenCount(0), TokenCount(0)),
                latency = LatencyMs(0),
            )
    }
}
