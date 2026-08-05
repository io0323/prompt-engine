package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditRepository
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.template.ExtendsFieldResolver
import promptengine.engine.compiler.ExtendsFieldResolverImpl
import promptengine.infrastructure.audit.InMemoryAuditRepository
import promptengine.infrastructure.audit.Slf4jAuditFailureHandler
import promptengine.infrastructure.messaging.InMemoryEventBusAdapter
import promptengine.infrastructure.persistence.JdbcAuditRepository
import promptengine.infrastructure.persistence.JdbcIdempotentCommandExecutor

/**
 * 冪等性実行・監査・イベント配送のDI配線（P9c、[RepositoryConfig]のKDoc参照。
 * detekt TooManyFunctions閾値対策で[PersistenceConfig.kt][promptengine.bootstrap.config]を分割）。
 *
 * [auditRepositoryProduction]/[auditRepositoryDefault]・[eventBusAdapter]は、`production`
 * プロファイルでInMemory実装が選択された場合に起動時エラーとする方針（ADR-0015決定7）を
 * `activeProfiles`経由で反映する。`EventBusAdapter`は本番向けの実装（Kafka互換Broker中継、
 * Issue #11）がまだ存在しないため、現状`production`プロファイルでは本アプリケーション自体が
 * 起動できない（意図した制約。P10で解消予定）。
 */
@Configuration
class AuditEventConfig {
    @Bean
    fun idempotentCommandExecutor(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
        objectMapper: ObjectMapper,
    ): IdempotentCommandExecutor = JdbcIdempotentCommandExecutor(jdbcTemplate, transactionTemplate, objectMapper)

    @Bean
    fun extendsFieldResolver(): ExtendsFieldResolver = ExtendsFieldResolverImpl()

    @Bean
    fun auditFailureHandler(): AuditFailureHandler = Slf4jAuditFailureHandler()

    /** 監査記録の本来の永続化先（`production`プロファイルではこちらを使う、ADR-0017）。 */
    @Bean
    @Profile("production")
    fun auditRepositoryProduction(
        jdbcTemplate: NamedParameterJdbcTemplate,
        objectMapper: ObjectMapper,
    ): AuditRepository = JdbcAuditRepository(jdbcTemplate, objectMapper)

    /**
     * ローカル開発・テスト用の既定（ADR-0015決定7）。`production`プロファイルでは
     * [auditRepositoryProduction]が選ばれるため生成されないが、万一の誤配線に備え
     * `InMemoryAuditRepository`自身もactiveProfilesを見て起動時エラーとする（多層防御）。
     */
    @Bean
    @Profile("!production")
    fun auditRepositoryDefault(environment: Environment): AuditRepository =
        InMemoryAuditRepository(environment.activeProfiles.toSet())

    @Bean
    fun eventBusAdapter(environment: Environment): EventBusAdapter =
        InMemoryEventBusAdapter(environment.activeProfiles.toSet())
}
