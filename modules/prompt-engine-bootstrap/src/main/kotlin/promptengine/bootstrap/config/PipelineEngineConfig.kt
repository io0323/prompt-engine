package promptengine.bootstrap.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.domain.composition.CompositionService
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.optimization.OptimizationEngine
import promptengine.domain.optimization.OptimizationRule
import promptengine.domain.parsing.OutputFormatter
import promptengine.domain.render.OutputFormat
import promptengine.domain.render.RenderEngine
import promptengine.domain.render.TemplateEngine
import promptengine.domain.template.TemplateRepository
import promptengine.domain.tokenizer.TokenizerPlugin
import promptengine.domain.validation.ValidationEngine
import promptengine.domain.validation.ValidationRule
import promptengine.domain.variable.SecretManagerAdapter
import promptengine.domain.variable.VariableResolverChain
import promptengine.engine.compiler.CompositionServiceImpl
import promptengine.engine.formatter.TextOutputFormatter
import promptengine.engine.optimization.CompressionRule
import promptengine.engine.optimization.ContextOptimizationRule
import promptengine.engine.optimization.ExpansionRule
import promptengine.engine.optimization.OptimizationEngineImpl
import promptengine.engine.optimization.TokenOptimizationRule
import promptengine.engine.render.RenderEngineImpl
import promptengine.engine.resolver.VariableResolverChainImpl
import promptengine.engine.validation.DependencyValidationRule
import promptengine.engine.validation.LengthValidationRule
import promptengine.engine.validation.ParameterValidationRule
import promptengine.engine.validation.PlaceholderValidationRule
import promptengine.engine.validation.SchemaValidationRule
import promptengine.engine.validation.ValidationEngineImpl
import promptengine.plugin.formatter.json.JsonOutputFormatter
import promptengine.plugin.validator.policy.PolicyValidationRule

/**
 * Composition/Validation/Optimization/RenderステージのEngine DI配線（P9c、
 * [PluginEngineConfig]のKDoc参照）。
 */
@Configuration
class PipelineEngineConfig {
    @Bean
    fun compositionService(
        templateRepository: TemplateRepository,
        fragmentRepository: FragmentRepository,
    ): CompositionService = CompositionServiceImpl(templateRepository, fragmentRepository)

    @Bean
    fun variableResolverChain(secretManagerAdapter: SecretManagerAdapter): VariableResolverChain =
        VariableResolverChainImpl.standard(secretManagerAdapter)

    @Bean
    fun policyValidationRule(
        @Value("\${pe.validation.banned-words:}") bannedWordsCsv: String,
    ): PolicyValidationRule =
        PolicyValidationRule(bannedWordsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() })

    @Bean
    fun validationEngine(
        tokenizerPlugin: TokenizerPlugin,
        policyValidationRule: PolicyValidationRule,
    ): ValidationEngine {
        val rules: List<ValidationRule> =
            listOf(
                SchemaValidationRule(),
                PlaceholderValidationRule(),
                ParameterValidationRule(),
                LengthValidationRule(tokenizerPlugin),
                policyValidationRule,
                DependencyValidationRule(),
            )
        return ValidationEngineImpl(rules)
    }

    @Bean
    fun optimizationEngine(tokenizerPlugin: TokenizerPlugin): OptimizationEngine {
        val rules: List<OptimizationRule> =
            listOf(
                TokenOptimizationRule(tokenizerPlugin),
                ContextOptimizationRule(tokenizerPlugin),
                CompressionRule(tokenizerPlugin),
                ExpansionRule(),
            )
        return OptimizationEngineImpl(rules, tokenizerPlugin)
    }

    @Bean
    fun outputFormatters(): Map<OutputFormat, OutputFormatter> {
        val text = TextOutputFormatter()
        return mapOf(
            OutputFormat.TEXT to text,
            OutputFormat.MARKDOWN to text,
            OutputFormat.JSON to JsonOutputFormatter(),
        )
    }

    @Bean
    fun renderEngine(
        templateEngine: TemplateEngine,
        tokenizerPlugin: TokenizerPlugin,
        outputFormatters: Map<OutputFormat, OutputFormatter>,
    ): RenderEngine = RenderEngineImpl(templateEngine, tokenizerPlugin, outputFormatters)
}
