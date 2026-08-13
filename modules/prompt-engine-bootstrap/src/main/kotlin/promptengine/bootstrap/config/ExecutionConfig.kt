package promptengine.bootstrap.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
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

/**
 * Execution段のDI配線と、`prompt-engine-application`のUseCaseが要求する[ModelProfile]・
 * [PipelineRequestFactory]の配線（P9c、[PluginEngineConfig]のKDoc参照）。
 *
 * APAPは独立基盤として別途構築する方針が確定した（ADR-0031）。M2-1a/M2-1cでPEP内に
 * 置いていた暫定Providerアダプタ（`plugins/execution-openai`）は削除済みで、
 * [executionAdapter]は現時点で[FakeExecutionAdapter]のみを構築する。
 * [ExecutionProviderProperties.provider]は`fake`/`apap`を将来にわたり受け付ける
 * 切替機構として残す——実APAP接続時、`apap`分岐に実アダプタ（APAP呼び出しクライアント、
 * `prompt-engine-infrastructure`）を実装する（ADR-0031参照）。
 */
@Configuration
@EnableConfigurationProperties(ModelProfileProperties::class, ExecutionProviderProperties::class)
class ExecutionConfig {
    /**
     * `activeProfiles`は`@Value("#{environment.activeProfiles}")`ではなく[Environment]の
     * 直接注入で取得する。前者はアクティブプロファイルを1つも指定せずに起動した場合（既定の
     * 起動経路）に`null`へ解決され、Kotlinの非null引数チェックで`NullPointerException`と
     * なりコンテキスト起動自体が失敗する不具合があった（9c、初回起動確認で発覚。
     * [Environment]は`ApplicationContext`が必ず提供する解決可能な依存であり型ベース注入なら
     * この問題が起きない）。
     *
     * [FakeExecutionAdapter]自身の`production`プロファイルガードは[providerProperties]の値に
     * 関わらず常に有効（`provider=fake`のまま本番投入すれば従来通り起動失敗する）。
     * `provider=apap`は将来のAPAP接続用に値としては認識するが、実装が無いためfail-fastする
     * （Issue #31、ADR-0031）。それ以外の未知の値もBean生成自体を失敗させる
     * （fail-fast、設定ミスを起動時に検知する）。
     */
    @Bean
    fun executionAdapter(
        environment: Environment,
        providerProperties: ExecutionProviderProperties,
    ): ExecutionAdapter {
        val delegate =
            when (providerProperties.provider) {
                FAKE_PROVIDER ->
                    FakeExecutionAdapter(
                        scenario = DEFAULT_FAKE_SCENARIO,
                        activeProfiles = environment.activeProfiles.toSet(),
                    )
                APAP_PROVIDER ->
                    error(
                        "promptengine.execution.provider=$APAP_PROVIDER is not yet implemented; " +
                            "APAP integration is tracked in Issue #31 and ADR-0031",
                    )
                else ->
                    error(
                        "Unknown promptengine.execution.provider: '${providerProperties.provider}' " +
                            "(expected '$FAKE_PROVIDER' or '$APAP_PROVIDER')",
                    )
            }
        return RetryingExecutionAdapter(delegate)
    }

    @Bean
    fun executionEngine(
        executionAdapter: ExecutionAdapter,
        outputFormatters: Map<OutputFormat, OutputFormatter>,
        tokenizerPlugin: TokenizerPlugin,
    ): ExecutionEngine = ExecutionCoordinator(executionAdapter, outputFormatters, tokenizerPlugin)

    /**
     * `modelProfile`（設計書§13.2「APAP登録プロファイル参照名」、例:`"gpt-class-large"`）が
     * 指す実体を解決するレジストリは、`outputSchemaRef`（ADR-0022）と同様、実APAPアダプタと
     * 一体で設計すべき永続化対象であり、M1で新設しない。`prompt-engine-interface`は要求された
     * プロファイル名の非空検証のみ行い、実際の[ModelProfile]は[ModelProfileProperties]
     * （`promptengine.model-profile.*`、M2-1c、ADR-0030決定1）から構築したこの1つの値を使う
     * （`modelProfile`の値によって挙動が変わらない、Stage 7 Optimization/Stage 9 Executionが
     * 動作するために構造上必要な値であり、中身の妥当性がクライアントの指定に依存しない）。
     */
    @Bean
    fun defaultModelProfile(modelProfileProperties: ModelProfileProperties): ModelProfile =
        ModelProfile(
            maxContextTokens = TokenCount(modelProfileProperties.maxContextTokens),
            tokenizerId = modelProfileProperties.tokenizerId,
            costPerToken = Cost(modelProfileProperties.costPerToken.toBigDecimal()),
        )

    /** [PipelineRequestFactory]を[defaultModelProfile]で構築する。 */
    @Bean
    fun pipelineRequestFactory(defaultModelProfile: ModelProfile): PipelineRequestFactory =
        PipelineRequestFactory(defaultModelProfile)

    companion object {
        private const val FAKE_PROVIDER = "fake"

        // internal: ExecutionConfigTestが値を直接文字列リテラルとして書かず本定数を参照する。
        internal const val APAP_PROVIDER = "apap"
        private val DEFAULT_FAKE_SCENARIO =
            FakeExecutionScenario.Success(
                content = "{}",
                usage = Usage(TokenCount(0), TokenCount(0)),
                latency = LatencyMs(0),
            )
    }
}
