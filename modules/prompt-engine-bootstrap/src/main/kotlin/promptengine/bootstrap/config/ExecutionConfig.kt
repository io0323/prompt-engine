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
import promptengine.domain.variable.SecretManagerAdapter
import promptengine.engine.execution.ExecutionCoordinator
import promptengine.engine.execution.RetryingExecutionAdapter
import promptengine.plugin.execution.fake.FakeExecutionAdapter
import promptengine.plugin.execution.fake.FakeExecutionScenario
import promptengine.plugin.execution.openai.OpenAiExecutionAdapter

/**
 * Execution段のDI配線と、`prompt-engine-application`のUseCaseが要求する[ModelProfile]・
 * [PipelineRequestFactory]の配線（P9c・M2-1c、[PluginEngineConfig]のKDoc参照）。
 *
 * [executionAdapter]は[ExecutionProviderProperties.provider]の値で[FakeExecutionAdapter]と
 * 暫定Providerアダプタ（`plugins/execution-openai`、ADR-0029・ADR-0030）を切り替える
 * （既定`fake`、M1からの挙動を変えない）。**本ファイルは、削除可能性を優先して
 * `plugins/execution-openai`に置いた暫定実装をComposition Rootとして具体的に参照する
 * 唯一の場所である**（`ProviderNameContainmentTest`のallowlistに本ファイルが含まれる理由。
 * ADR-0030決定4「削除範囲の再確認」参照。実APAP接続時にこの暫定実装を取り除く際は、
 * `plugins/execution-openai`ディレクトリ・bootstrapの依存宣言・本ファイルの参照の3点を
 * 揃えて削除する必要がある）。
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
     * 関わらず常に有効（`provider=fake`のまま本番投入すれば従来通り起動失敗する、ADR-0030決定4）。
     * `provider`が未知の値の場合はBean生成自体を失敗させる（fail-fast、設定ミスを起動時に検知する）。
     */
    @Bean
    fun executionAdapter(
        environment: Environment,
        secretManagerAdapter: SecretManagerAdapter,
        providerProperties: ExecutionProviderProperties,
    ): ExecutionAdapter {
        val delegate =
            when (providerProperties.provider) {
                FAKE_PROVIDER ->
                    FakeExecutionAdapter(
                        scenario = DEFAULT_FAKE_SCENARIO,
                        activeProfiles = environment.activeProfiles.toSet(),
                    )
                REAL_PROVIDER -> buildRealAdapter(secretManagerAdapter, providerProperties.modelName)
                else ->
                    error(
                        "Unknown promptengine.execution.provider: '${providerProperties.provider}' " +
                            "(expected '$FAKE_PROVIDER' or '$REAL_PROVIDER')",
                    )
            }
        return RetryingExecutionAdapter(delegate)
    }

    /**
     * APIキーは[SecretManagerAdapter]（既存Bean、[PluginEngineConfig]）経由で解決する
     * （新しい供給経路を作らない、ADR-0030決定4）。未設定の場合はBean生成自体を失敗させ、
     * 最初のリクエストで初めて落ちる事態を避ける（fail-fast）。
     *
     * `null`だけでなく空白文字列も拒否する（CodeRabbitレビュー指摘）:
     * [EnvironmentSecretManagerAdapter][promptengine.infrastructure.adapter.secret.EnvironmentSecretManagerAdapter]は
     * 環境変数が「設定されているが空文字列」の場合も非nullの[SensitiveValue]を返すため、
     * `checkNotNull`だけでは検知できない。Helmの`secret.executionApiKey`既定値が空文字列
     * であるため、この経路は現実的に起こりうる誤設定（値を入れ忘れたまま`provider=openai`に
     * 切り替えた場合）である。
     */
    private fun buildRealAdapter(
        secretManagerAdapter: SecretManagerAdapter,
        modelName: String,
    ): ExecutionAdapter {
        val apiKey = secretManagerAdapter.getSecret(API_KEY_SECRET_NAME)
        check(apiKey != null && apiKey.expose().isNotBlank()) { missingApiKeyMessage() }
        return OpenAiExecutionAdapter(apiKey = apiKey, model = modelName)
    }

    private fun missingApiKeyMessage(): String =
        "promptengine.execution.provider=$REAL_PROVIDER requires the '$API_KEY_SECRET_NAME' secret to be configured"

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

        // internal: ExecutionConfigTestが実プロバイダ選択時の挙動を検証する際、この値を
        // 直接文字列リテラルとして書かず本定数を参照する（ProviderNameContainmentTestの
        // allowlistをExecutionConfig.kt自身に限定し続けるため、ADR-0030決定4）。
        internal const val REAL_PROVIDER = "openai"
        internal const val API_KEY_SECRET_NAME = "OPENAI_API_KEY"
        private val DEFAULT_FAKE_SCENARIO =
            FakeExecutionScenario.Success(
                content = "{}",
                usage = Usage(TokenCount(0), TokenCount(0)),
                latency = LatencyMs(0),
            )
    }
}
