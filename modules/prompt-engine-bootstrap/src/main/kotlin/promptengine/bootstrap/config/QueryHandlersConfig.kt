package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.application.query.AuditLogsHandler
import promptengine.application.query.DependenciesHandler
import promptengine.application.query.DiffHandler
import promptengine.application.query.GetPromptHandler
import promptengine.application.query.GetVersionHandler
import promptengine.application.query.MetricsHandler
import promptengine.application.query.SearchPromptsHandler
import promptengine.domain.audit.AuditRepository
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.metrics.MetricsRepository
import promptengine.domain.prompt.PromptMetadataRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptSearchRepository

/**
 * P9bのQueryハンドラのDI配線（P9c）。設計書§13.1のエンドポイントと1:1対応する
 * （[CommandHandlersConfig]のKDoc参照。detekt TooManyFunctions閾値対策でCommand/Queryに分割）。
 */
@Configuration
class QueryHandlersConfig {
    @Bean
    fun getPromptHandler(
        promptRepository: PromptRepository,
        promptMetadataRepository: PromptMetadataRepository,
    ): GetPromptHandler = GetPromptHandler(promptRepository, promptMetadataRepository)

    @Bean
    fun getVersionHandler(promptRepository: PromptRepository): GetVersionHandler = GetVersionHandler(promptRepository)

    @Bean
    fun diffHandler(promptRepository: PromptRepository): DiffHandler = DiffHandler(promptRepository)

    @Bean
    fun searchPromptsHandler(promptSearchRepository: PromptSearchRepository): SearchPromptsHandler =
        SearchPromptsHandler(promptSearchRepository)

    @Bean
    fun dependenciesHandler(dependencyRepository: DependencyRepository): DependenciesHandler =
        DependenciesHandler(dependencyRepository)

    @Bean
    fun metricsHandler(metricsRepository: MetricsRepository): MetricsHandler = MetricsHandler(metricsRepository)

    @Bean
    fun auditLogsHandler(auditRepository: AuditRepository): AuditLogsHandler = AuditLogsHandler(auditRepository)
}
