package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.infrastructure.persistence.JdbcBenchmarkItemResultRepository
import promptengine.infrastructure.persistence.JdbcBenchmarkRepository
import promptengine.infrastructure.persistence.JdbcGoldenDatasetRepository

/**
 * Benchmark/GoldenDataset Repository系のDI配線（ADR-0035）。[RepositoryConfig]から
 * detekt TooManyFunctions閾値対策で分離した。
 */
@Configuration
class BenchmarkRepositoryConfig {
    @Bean
    fun goldenDatasetRepository(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
        objectMapper: ObjectMapper,
    ): GoldenDatasetRepository = JdbcGoldenDatasetRepository(jdbcTemplate, transactionTemplate, objectMapper)

    @Bean
    fun benchmarkRepository(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
    ): BenchmarkRepository = JdbcBenchmarkRepository(jdbcTemplate, transactionTemplate)

    @Bean
    fun benchmarkItemResultRepository(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
    ): BenchmarkItemResultRepository = JdbcBenchmarkItemResultRepository(jdbcTemplate, transactionTemplate)
}
