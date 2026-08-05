package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * トランザクション基盤のDI配線（P9c）。`prompt-engine-infrastructure`が持つ`spring-boot-starter-
 * data-jdbc`により`DataSource`/`NamedParameterJdbcTemplate`はSpring Bootが自動構成するが、
 * `TransactionTemplate`は明示的な`@Bean`が必要（Spring Bootは`PlatformTransactionManager`までしか
 * 自動構成しない、[RepositoryConfig]のKDoc参照）。
 */
@Configuration
class TransactionConfig {
    @Bean
    fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(transactionManager)

    @Bean
    fun jdbcTransactionManager(dataSource: DataSource): PlatformTransactionManager = JdbcTransactionManager(dataSource)
}
