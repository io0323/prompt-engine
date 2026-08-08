package promptengine.bootstrap.config

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import promptengine.infrastructure.messaging.DomainEventOutboxSource
import promptengine.infrastructure.messaging.EventBusOutboxSource
import promptengine.infrastructure.messaging.EventProducer
import promptengine.infrastructure.messaging.KafkaEventProducer
import promptengine.infrastructure.messaging.OutboxRelayer
import promptengine.infrastructure.messaging.OutboxSource
import java.net.InetAddress
import java.time.Duration
import java.util.Properties
import java.util.UUID

private const val EVENT_BUS_SOURCE_QUALIFIER = "eventBusOutboxSource"
private const val DOMAIN_EVENT_SOURCE_QUALIFIER = "domainEventOutboxSource"

/** [OutboxRelayScheduler]の2ジョブ分（`event_bus_outbox`用・既存`outbox`用）。 */
private const val SCHEDULER_POOL_SIZE = 2

/**
 * このプロセスのクレーム識別子（`claimed_by`、ADR-0025決定3）。プロセス起動ごとに1回生成し、
 * 複数インスタンス下でクラッシュしたインスタンスのクレームを他インスタンスが検出できるようにする。
 * 生の`String`ではなく専用の型として持ち、将来増えうる他の`String` Beanとの型ベース解決の
 * 曖昧さを避ける。
 */
data class OutboxRelayInstanceId(val value: String)

/**
 * Outbox → Broker中継のDI配線（ADR-0025決定2・3・5・9、Issue #11クローズ）。`production`
 * プロファイルのみで有効化する（[AuditEventConfig]の`eventBusAdapterProduction`/
 * `eventBusAdapterDefault`と同じ分岐方針）。非productionプロファイルは中継自体を起動しない
 * （既存`outbox`が非productionで中継されず溜まり続ける挙動はADR-0006時点から変化しない、
 * ADR-0025決定5）。
 *
 * [EnableScheduling]をこのクラス自体（`production`プロファイル限定）に付与することで、
 * `@Scheduled`処理系（[org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor]）
 * も非productionでは登録されない。
 */
@Configuration
@Profile("production")
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties::class)
class OutboxRelayConfig {
    /**
     * [OutboxRelayScheduler]の2つの`@Scheduled`メソッド用スレッドプール（CodeRabbitレビュー指摘）。
     * Springの既定`TaskScheduler`はプールサイズ1であり、`event_bus_outbox`用と既存`outbox`用の
     * 2ジョブが単一スレッドで直列実行されてしまう（片方のBroker送信が遅延すると、もう片方の
     * ポーリングサイクルも止まる）。プールサイズ2でこの2ジョブが独立して並行実行されるようにする。
     */
    @Bean
    fun outboxRelayTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = SCHEDULER_POOL_SIZE
            threadNamePrefix = "outbox-relay-"
            initialize()
        }

    @Bean
    fun outboxRelayInstanceId(): OutboxRelayInstanceId = OutboxRelayInstanceId("${hostName()}-${UUID.randomUUID()}")

    @Bean(destroyMethod = "close")
    fun kafkaProducer(
        @Value("\${promptengine.eventbus.kafka.bootstrap-servers:localhost:9092}") bootstrapServers: String,
    ): Producer<String, String> {
        val props =
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.ACKS_CONFIG, "all")
            }
        return KafkaProducer(props)
    }

    @Bean
    fun eventProducer(producer: Producer<String, String>): EventProducer = KafkaEventProducer(producer)

    @Bean
    @Qualifier(EVENT_BUS_SOURCE_QUALIFIER)
    fun eventBusOutboxSource(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
    ): OutboxSource = EventBusOutboxSource(jdbcTemplate, transactionTemplate)

    @Bean
    @Qualifier(DOMAIN_EVENT_SOURCE_QUALIFIER)
    fun domainEventOutboxSource(
        jdbcTemplate: NamedParameterJdbcTemplate,
        transactionTemplate: TransactionTemplate,
    ): OutboxSource = DomainEventOutboxSource(jdbcTemplate, transactionTemplate)

    /** `event_bus_outbox`（Pipeline通知イベント）を中継するRelayer。 */
    @Bean
    fun eventBusOutboxRelayer(
        @Qualifier(EVENT_BUS_SOURCE_QUALIFIER) source: OutboxSource,
        producer: EventProducer,
        instanceId: OutboxRelayInstanceId,
        properties: OutboxRelayProperties,
    ): OutboxRelayer =
        OutboxRelayer(
            source,
            producer,
            instanceId.value,
            Duration.ofSeconds(properties.claimTimeoutSeconds),
            properties.batchSize,
        )

    /** 既存`outbox`（Prompt Aggregate状態遷移イベント）を中継するRelayer。 */
    @Bean
    fun domainEventOutboxRelayer(
        @Qualifier(DOMAIN_EVENT_SOURCE_QUALIFIER) source: OutboxSource,
        producer: EventProducer,
        instanceId: OutboxRelayInstanceId,
        properties: OutboxRelayProperties,
    ): OutboxRelayer =
        OutboxRelayer(
            source,
            producer,
            instanceId.value,
            Duration.ofSeconds(properties.claimTimeoutSeconds),
            properties.batchSize,
        )

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
}
