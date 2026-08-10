package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.domain.context.ContextResolverChain
import promptengine.domain.render.TemplateEngine
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.variable.SecretManagerAdapter
import promptengine.engine.render.DefaultTemplateEngine
import promptengine.engine.resolver.ContextResolverImpl
import promptengine.infrastructure.adapter.secret.EnvironmentSecretManagerAdapter
import promptengine.plugin.tokenizer.approx.ApproxTokenizerPlugin

/**
 * Plugin/末端エンジンのDI配線（P9c、CLAUDE.md「具象クラスのDI結線はprompt-engine-bootstrapの
 * Configurationクラスでのみ行う」。[PipelineEngineConfig]・[ExecutionConfig]のKDoc参照。
 * detekt TooManyFunctions閾値対策で[EngineConfig.kt][promptengine.bootstrap.config]を分割）。
 *
 * `PipelineTracer`のDI配線は[OtelTracerConfig]（Issue #38、ADR-0027決定2）へ移した
 * （実OpenTelemetry実装が入ったため、`NoopPipelineTracer`をここで束ねる必要が無くなった）。
 */
@Configuration
class PluginEngineConfig {
    @Bean
    fun tokenizerPlugin(): TokenizerPlugin = ApproxTokenizerPlugin()

    @Bean
    fun secretManagerAdapter(): SecretManagerAdapter = EnvironmentSecretManagerAdapter()

    @Bean
    fun contextResolverChain(): ContextResolverChain = ContextResolverImpl.standard()

    @Bean
    fun templateEngine(): TemplateEngine = DefaultTemplateEngine()
}
