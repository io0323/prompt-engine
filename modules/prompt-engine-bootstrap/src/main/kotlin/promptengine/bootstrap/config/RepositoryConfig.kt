package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.metrics.MetricsRepository
import promptengine.domain.prompt.PromptAliasRepository
import promptengine.domain.prompt.PromptMetadataRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptSearchRepository
import promptengine.domain.template.TemplateRepository
import promptengine.infrastructure.persistence.EventStorePromptRepository
import promptengine.infrastructure.persistence.JdbcDependencyRepository
import promptengine.infrastructure.persistence.JdbcFragmentRepository
import promptengine.infrastructure.persistence.JdbcMetricsRepository
import promptengine.infrastructure.persistence.JdbcPromptAliasRepository
import promptengine.infrastructure.persistence.JdbcPromptMetadataRepository
import promptengine.infrastructure.persistence.JdbcPromptSearchRepository
import promptengine.infrastructure.persistence.JdbcTemplateRepository

/**
 * Repository系のDI配線（P9c、[TransactionConfig]・[AuditEventConfig]のKDoc参照。
 * detekt TooManyFunctions閾値対策で[PersistenceConfig.kt][promptengine.bootstrap.config]を分割）。
 */
@Configuration
class RepositoryConfig {
    @Bean
    fun promptRepository(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
        objectMapper: ObjectMapper,
    ): PromptRepository = EventStorePromptRepository(jdbcTemplate, transactionTemplate, objectMapper)

    @Bean
    fun templateRepository(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
        objectMapper: ObjectMapper,
    ): TemplateRepository = JdbcTemplateRepository(jdbcTemplate, transactionTemplate, objectMapper)

    @Bean
    fun fragmentRepository(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
        objectMapper: ObjectMapper,
    ): FragmentRepository = JdbcFragmentRepository(jdbcTemplate, transactionTemplate, objectMapper)

    @Bean
    fun promptAliasRepository(jdbcTemplate: NamedParameterJdbcTemplate): PromptAliasRepository =
        JdbcPromptAliasRepository(jdbcTemplate)

    @Bean
    fun promptMetadataRepository(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
        objectMapper: ObjectMapper,
    ): PromptMetadataRepository = JdbcPromptMetadataRepository(jdbcTemplate, transactionTemplate, objectMapper)

    @Bean
    fun dependencyRepository(jdbcTemplate: NamedParameterJdbcTemplate): DependencyRepository =
        JdbcDependencyRepository(jdbcTemplate)

    @Bean
    fun metricsRepository(jdbcTemplate: NamedParameterJdbcTemplate): MetricsRepository =
        JdbcMetricsRepository(jdbcTemplate)

    @Bean
    fun promptSearchRepository(jdbcTemplate: NamedParameterJdbcTemplate): PromptSearchRepository =
        JdbcPromptSearchRepository(jdbcTemplate)
}
