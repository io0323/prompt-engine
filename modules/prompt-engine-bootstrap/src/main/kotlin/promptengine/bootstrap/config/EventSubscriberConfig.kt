package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import promptengine.domain.audit.AuditRepository
import promptengine.domain.cache.PromptCache
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.evaluation.EvaluationEngine
import promptengine.domain.evaluation.EvaluationRepository
import promptengine.domain.evaluation.ExecutionLogRepository
import promptengine.domain.event.EventBusAdapter
import promptengine.domain.search.PromptSearchIndexer
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import promptengine.infrastructure.subscriber.AuditEngine
import promptengine.infrastructure.subscriber.CacheInvalidationPayloadCodec
import promptengine.infrastructure.subscriber.CacheInvalidationSubscriber
import promptengine.infrastructure.subscriber.EvaluationSubscriber
import promptengine.infrastructure.subscriber.ExecutionLogSubscriber
import promptengine.infrastructure.subscriber.PromptExecutedPayloadCodec
import promptengine.infrastructure.subscriber.SearchIndexSubscriber

/**
 * 5つの[promptengine.domain.event.EventSubscriber]実装のDI配線（ADR-0026決定1）。
 *
 * Brokerへの接続・ポーリング駆動は[SubscriberConfig]の責務。ここで定義するのは
 * イベント1件を処理するロジックだけを持つ購読側そのもの（[SubscriberConfig]から
 * 分けているのはdetekt TooManyFunctions閾値対策。[AuditEventConfig]・
 * [QueryHandlersConfig]が同じ理由で分割されているのと同じ）。
 *
 * [SubscriberConfig]と同じく`production`プロファイル限定。これらのBeanを参照するのは
 * 同じく`production`限定の`KafkaSubscriberRunner`のみであり、非productionで構築しても
 * 誰も駆動しないため。
 */
@Configuration
@Profile("production")
class EventSubscriberConfig {
    @Bean
    fun auditEngine(
        auditRepository: AuditRepository,
        sanitizer: SecretMaskingJsonSanitizer,
    ): AuditEngine = AuditEngine(auditRepository, sanitizer)

    @Bean
    fun executionLogSubscriber(
        executionLogRepository: ExecutionLogRepository,
        payloadCodec: PromptExecutedPayloadCodec,
    ): ExecutionLogSubscriber = ExecutionLogSubscriber(executionLogRepository, payloadCodec)

    @Bean
    fun evaluationSubscriber(
        evaluationEngine: EvaluationEngine,
        evaluationRepository: EvaluationRepository,
        eventBusAdapter: EventBusAdapter,
        payloadCodec: PromptExecutedPayloadCodec,
    ): EvaluationSubscriber =
        EvaluationSubscriber(evaluationEngine, evaluationRepository, eventBusAdapter, payloadCodec)

    @Bean
    fun cacheInvalidationSubscriber(
        promptCache: PromptCache,
        dependencyRepository: DependencyRepository,
        payloadCodec: CacheInvalidationPayloadCodec,
    ): CacheInvalidationSubscriber = CacheInvalidationSubscriber(promptCache, dependencyRepository, payloadCodec)

    @Bean
    fun searchIndexSubscriber(searchIndexer: PromptSearchIndexer): SearchIndexSubscriber =
        SearchIndexSubscriber(searchIndexer)
}
