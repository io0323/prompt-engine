package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditRepository
import promptengine.domain.dlq.DeadLetterQueueRepository
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.template.ExtendsFieldResolver
import promptengine.engine.compiler.ExtendsFieldResolverImpl
import promptengine.infrastructure.audit.DeadLetterQueueAuditFailureHandler
import promptengine.infrastructure.audit.InMemoryAuditRepository
import promptengine.infrastructure.audit.Slf4jAuditFailureHandler
import promptengine.infrastructure.messaging.InMemoryEventBusAdapter
import promptengine.infrastructure.messaging.OutboxEventBusAdapter
import promptengine.infrastructure.persistence.JdbcAuditRepository
import promptengine.infrastructure.persistence.JdbcIdempotentCommandExecutor

/**
 * 冪等性実行・監査・イベント配送のDI配線（P9c、[RepositoryConfig]のKDoc参照。
 * detekt TooManyFunctions閾値対策で[PersistenceConfig.kt][promptengine.bootstrap.config]を分割）。
 *
 * [auditRepositoryProduction]/[auditRepositoryDefault]・[eventBusAdapterProduction]/
 * [eventBusAdapterDefault]は、`production`プロファイルでInMemory実装が選択された場合に
 * 起動時エラーとする方針（ADR-0015決定7）を`activeProfiles`経由で反映する。`EventBusAdapter`の
 * 本番実装（[OutboxEventBusAdapter]、`event_bus_outbox`への書き込みのみ。実際のBroker中継は
 * [OutboxRelayConfig][promptengine.bootstrap.config.OutboxRelayConfig]が配線する
 * `OutboxRelayer`が非同期に行う）をADR-0025（P10a、Issue #35）で追加し、`production`
 * プロファイルが`EventBusAdapter`起因では起動失敗しなくなった。
 *
 * [idempotentCommandExecutor]は`IdempotencyClaimProperties`（`promptengine.idempotency.*`）で
 * `executeLongRunning`のクレームタイムアウトを設定する（Issue #50、ADR-0027）。
 * `prompt-engine-infrastructure`はbootstrap配下のPropertiesクラスに依存できない
 * （CLAUDE.mdのモジュール依存規約: infrastructureはdomainのInterfaceを実装する側であり、
 * bootstrapへの逆方向依存は作らない）ため、`JdbcIdempotentCommandExecutor`自体は
 * プリミティブ型の`claimTimeoutSeconds`のみを受け取り、値の注入はこのConfiguration層で行う。
 */
@Configuration
@EnableConfigurationProperties(IdempotencyClaimProperties::class)
class AuditEventConfig {
    @Bean
    fun idempotentCommandExecutor(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
        objectMapper: ObjectMapper,
        idempotencyClaimProperties: IdempotencyClaimProperties,
    ): IdempotentCommandExecutor =
        JdbcIdempotentCommandExecutor(
            jdbcTemplate,
            transactionTemplate,
            objectMapper,
            idempotencyClaimProperties.claimTimeoutSeconds,
        )

    @Bean
    fun extendsFieldResolver(): ExtendsFieldResolver = ExtendsFieldResolverImpl()

    /**
     * Pipeline Stage 12（Audit）の書き込み失敗を`dead_letter_queue`へ退避する
     * （設計書§2.6ステージ12「失敗時はDLQ退避（本流は止めない）」、Issue #37クローズ、
     * ADR-0026決定2）。P10b以前はログ出力のみの[Slf4jAuditFailureHandler]が暫定実装だった。
     *
     * [Slf4jAuditFailureHandler]はdelegateとして残す。DLQ側のログ（`dead_letter_enqueued`）は
     * `event_id`/`subscriber_name`中心で、Pipeline文脈（traceId / promptKey / mode）を
     * 持たないため、両方を出した方が運用時の追跡が容易になる。
     */
    @Bean
    fun auditFailureHandler(
        deadLetterQueueRepository: DeadLetterQueueRepository,
        objectMapper: ObjectMapper,
    ): AuditFailureHandler =
        DeadLetterQueueAuditFailureHandler(
            deadLetterQueueRepository = deadLetterQueueRepository,
            objectMapper = objectMapper,
            delegate = Slf4jAuditFailureHandler(),
        )

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

    /** イベント配送の本来の実装（`production`プロファイルではこちらを使う、ADR-0025決定6）。 */
    @Bean
    @Profile("production")
    fun eventBusAdapterProduction(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
        objectMapper: ObjectMapper,
    ): EventBusAdapter = OutboxEventBusAdapter(jdbcTemplate, transactionTemplate, objectMapper)

    /**
     * ローカル開発・テスト用の既定（ADR-0015決定7）。`production`プロファイルでは
     * [eventBusAdapterProduction]が選ばれるため生成されないが、万一の誤配線に備え
     * `InMemoryEventBusAdapter`自身もactiveProfilesを見て起動時エラーとする（多層防御）。
     */
    @Bean
    @Profile("!production")
    fun eventBusAdapterDefault(environment: Environment): EventBusAdapter =
        InMemoryEventBusAdapter(environment.activeProfiles.toSet())
}
