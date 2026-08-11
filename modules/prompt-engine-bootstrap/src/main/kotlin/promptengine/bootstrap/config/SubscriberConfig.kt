package promptengine.bootstrap.config

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import promptengine.domain.dlq.DeadLetterQueueRepository
import promptengine.domain.event.EventSubscriber
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import promptengine.infrastructure.messaging.EventEnvelopeCodec
import promptengine.infrastructure.subscriber.AuditEngine
import promptengine.infrastructure.subscriber.CacheInvalidationSubscriber
import promptengine.infrastructure.subscriber.EvaluationSubscriber
import promptengine.infrastructure.subscriber.ExecutionLogSubscriber
import promptengine.infrastructure.subscriber.KafkaSubscriberRunner
import promptengine.infrastructure.subscriber.SearchIndexSubscriber
import promptengine.infrastructure.subscriber.SubscriberDeadLetterRecorder
import java.time.Duration
import java.util.Properties

/** [SubscriberScheduler]の5ジョブ分。 */
private const val SUBSCRIBER_POOL_SIZE = 5

private const val AUDIT_RUNNER = "auditSubscriberRunner"
private const val EXECUTION_LOG_RUNNER = "executionLogSubscriberRunner"
private const val EVALUATION_RUNNER = "evaluationSubscriberRunner"
private const val CACHE_INVALIDATION_RUNNER = "cacheInvalidationSubscriberRunner"
private const val SEARCH_INDEX_RUNNER = "searchIndexSubscriberRunner"

/**
 * Broker購読側のDI配線（ADR-0026決定1、Issue #37・#48と併せてP10bのスコープ）。
 *
 * [OutboxRelayConfig]と同じく`production`プロファイルのみで有効化する。非productionでは
 * 実Brokerが存在せず、`InMemoryEventBusAdapter`が使われるため購読対象自体が無い
 * （ADR-0025決定5と同じ扱い。回帰ではなく対象範囲外）。
 *
 * 各購読側は**自分専用のconsumer group**を持つ（[EventSubscriber.name]をgroup IDに使う）。
 * これにより同じTopicの同じメッセージが購読側ごとに独立して配送される（Kafkaの
 * consumer groupの意味論）。`AuditEngine`が`pe.execution`を読んでも
 * `ExecutionLogSubscriber`の取り分が減らない、という配送独立性がここで担保される。
 *
 * `promptengine.scheduler.enabled=false`（既定`true`）でこのConfiguration自体を丸ごと
 * 無効化できる（[OutboxRelayConfig]と同じ理由、P11のHelm `api`/`admin` Deployment向け）。
 */
@Configuration
@Profile("production")
@ConditionalOnProperty(
    prefix = "promptengine.scheduler",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableScheduling
@EnableConfigurationProperties(SubscriberProperties::class)
class SubscriberConfig {
    /**
     * [SubscriberScheduler]の5つの`@Scheduled`メソッド用スレッドプール。
     * Springの既定`TaskScheduler`はプールサイズ1であり、5ジョブが直列化されると
     * 1つの購読側のBroker応答待ちが他4つのポーリングを止めてしまう
     * （[OutboxRelayConfig.outboxRelayTaskScheduler]と同じ理由）。
     */
    @Bean
    fun subscriberTaskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = SUBSCRIBER_POOL_SIZE
            threadNamePrefix = "event-subscriber-"
            initialize()
        }

    @Bean(name = [AUDIT_RUNNER], destroyMethod = "close")
    fun auditSubscriberRunner(
        subscriber: AuditEngine,
        deps: SubscriberRunnerDependencies,
    ): KafkaSubscriberRunner = deps.runnerFor(subscriber)

    @Bean(name = [EXECUTION_LOG_RUNNER], destroyMethod = "close")
    fun executionLogSubscriberRunner(
        subscriber: ExecutionLogSubscriber,
        deps: SubscriberRunnerDependencies,
    ): KafkaSubscriberRunner = deps.runnerFor(subscriber)

    @Bean(name = [EVALUATION_RUNNER], destroyMethod = "close")
    fun evaluationSubscriberRunner(
        subscriber: EvaluationSubscriber,
        deps: SubscriberRunnerDependencies,
    ): KafkaSubscriberRunner = deps.runnerFor(subscriber)

    @Bean(name = [CACHE_INVALIDATION_RUNNER], destroyMethod = "close")
    fun cacheInvalidationSubscriberRunner(
        subscriber: CacheInvalidationSubscriber,
        deps: SubscriberRunnerDependencies,
    ): KafkaSubscriberRunner = deps.runnerFor(subscriber)

    @Bean(name = [SEARCH_INDEX_RUNNER], destroyMethod = "close")
    fun searchIndexSubscriberRunner(
        subscriber: SearchIndexSubscriber,
        deps: SubscriberRunnerDependencies,
    ): KafkaSubscriberRunner = deps.runnerFor(subscriber)

    /** 購読側で処理に失敗したメッセージのDLQ退避（Issue #37、ADR-0026決定2）。 */
    @Bean
    fun subscriberDeadLetterRecorder(
        deadLetterQueueRepository: DeadLetterQueueRepository,
        envelopeCodec: EventEnvelopeCodec,
        sanitizer: SecretMaskingJsonSanitizer,
    ): SubscriberDeadLetterRecorder = SubscriberDeadLetterRecorder(deadLetterQueueRepository, envelopeCodec, sanitizer)

    /**
     * 5つのRunner Beanが共通して必要とする協力者をまとめる。個々の`@Bean`メソッドが
     * 同じ引数の並びを繰り返すのを避けるため。
     */
    @Bean
    fun subscriberRunnerDependencies(
        @Value("\${promptengine.eventbus.kafka.bootstrap-servers:localhost:9092}") bootstrapServers: String,
        envelopeCodec: EventEnvelopeCodec,
        deadLetterRecorder: SubscriberDeadLetterRecorder,
        properties: SubscriberProperties,
    ): SubscriberRunnerDependencies =
        SubscriberRunnerDependencies(bootstrapServers, envelopeCodec, deadLetterRecorder, properties)

    @Bean
    fun subscriberScheduler(
        @Qualifier(AUDIT_RUNNER) auditRunner: KafkaSubscriberRunner,
        @Qualifier(EXECUTION_LOG_RUNNER) executionLogRunner: KafkaSubscriberRunner,
        @Qualifier(EVALUATION_RUNNER) evaluationRunner: KafkaSubscriberRunner,
        @Qualifier(CACHE_INVALIDATION_RUNNER) cacheInvalidationRunner: KafkaSubscriberRunner,
        @Qualifier(SEARCH_INDEX_RUNNER) searchIndexRunner: KafkaSubscriberRunner,
    ): SubscriberScheduler =
        SubscriberScheduler(
            auditRunner,
            executionLogRunner,
            evaluationRunner,
            cacheInvalidationRunner,
            searchIndexRunner,
        )
}

/**
 * [KafkaSubscriberRunner]の生成に必要な共通の協力者一式（[SubscriberConfig]専用）。
 * [runnerFor]が購読側ごとに専用の`KafkaConsumer`（group ID = [EventSubscriber.name]）を
 * 構築する。
 */
class SubscriberRunnerDependencies(
    private val bootstrapServers: String,
    private val envelopeCodec: EventEnvelopeCodec,
    private val deadLetterRecorder: SubscriberDeadLetterRecorder,
    private val properties: SubscriberProperties,
) {
    fun runnerFor(subscriber: EventSubscriber): KafkaSubscriberRunner =
        KafkaSubscriberRunner(
            subscriber = subscriber,
            consumer = consumerFor(subscriber),
            envelopeCodec = envelopeCodec,
            deadLetterRecorder = deadLetterRecorder,
            pollTimeout = Duration.ofMillis(properties.pollTimeoutMs),
        )

    /**
     * 自動コミットは無効にする。[KafkaSubscriberRunner]が1バッチ処理後に明示的に
     * `commitSync`する（処理前にオフセットが進んでイベントを取りこぼすのを避けるため）。
     * `auto.offset.reset=earliest`は、購読側を新規追加した際に既存のイベントも
     * 監査・評価対象に含めるため。
     */
    private fun consumerFor(subscriber: EventSubscriber): Consumer<String, String> {
        val props =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, subscriber.name)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.maxPollRecords)
            }
        return KafkaConsumer(props)
    }
}
