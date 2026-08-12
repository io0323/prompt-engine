package promptengine.bootstrap.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.support.TestPropertySourceUtils
import promptengine.domain.dlq.DeadLetterQueueRepository
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import promptengine.infrastructure.messaging.EventEnvelopeCodec
import promptengine.infrastructure.subscriber.AuditEngine
import promptengine.infrastructure.subscriber.CacheInvalidationSubscriber
import promptengine.infrastructure.subscriber.EvaluationSubscriber
import promptengine.infrastructure.subscriber.ExecutionLogSubscriber
import promptengine.infrastructure.subscriber.SearchIndexSubscriber

/**
 * [SubscriberConfig]のDI配線を検証する（ADR-0026決定1、P11で追加した
 * `promptengine.scheduler.enabled`トグルの回帰テスト、[OutboxRelayConfig.outboxRelayScheduler]と
 * 同じ形）。`production`プロファイル下での本来の起動経路は[FakeExecutionAdapter]のガード
 * （ADR-0015）により必ず失敗し、[promptengine.bootstrap.PromptEngineApplicationContextLoadTest]
 * では検証対象外のため、`SubscriberConfig`単体を独立した最小コンテキストで直接確認する。
 */
class SubscriberConfigWiringTest {
    @Test
    fun `production下でpromptengine_scheduler_enabled未設定ならSubscriberConfigが有効化される`() {
        val context = AnnotationConfigApplicationContext()
        context.environment.setActiveProfiles("production")
        context.register(SubscriberConfig::class.java, StubSubscriberBeansConfig::class.java)
        context.refresh()

        try {
            context.getBean(SubscriberScheduler::class.java) shouldNotBe null
        } finally {
            context.close()
        }
    }

    @Test
    fun `promptengine_scheduler_enabled=falseならSubscriberConfig自体が丸ごと無効化される`() {
        val context = AnnotationConfigApplicationContext()
        context.environment.setActiveProfiles("production")
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "promptengine.scheduler.enabled=false")
        // Configuration自体がConditionalOnPropertyでスキップされるため、
        // StubSubscriberBeansConfigの協力者Beanは無くてもよいが、対称性のため揃えておく。
        context.register(SubscriberConfig::class.java, StubSubscriberBeansConfig::class.java)
        context.refresh()

        try {
            shouldThrow<NoSuchBeanDefinitionException> {
                context.getBean(SubscriberScheduler::class.java)
            }
        } finally {
            context.close()
        }
    }

    @Configuration
    private class StubSubscriberBeansConfig {
        @Bean
        fun auditEngine(): AuditEngine = mockk(relaxed = true) { every { name } returns "audit" }

        @Bean
        fun executionLogSubscriber(): ExecutionLogSubscriber =
            mockk(relaxed = true) { every { name } returns "execution-log" }

        @Bean
        fun evaluationSubscriber(): EvaluationSubscriber = mockk(relaxed = true) { every { name } returns "evaluation" }

        @Bean
        fun cacheInvalidationSubscriber(): CacheInvalidationSubscriber =
            mockk(relaxed = true) { every { name } returns "cache-invalidation" }

        @Bean
        fun searchIndexSubscriber(): SearchIndexSubscriber =
            mockk(relaxed = true) { every { name } returns "search-index" }

        @Bean
        fun deadLetterQueueRepository(): DeadLetterQueueRepository = mockk(relaxed = true)

        @Bean
        fun eventEnvelopeCodec(): EventEnvelopeCodec = mockk(relaxed = true)

        @Bean
        fun secretMaskingJsonSanitizer(): SecretMaskingJsonSanitizer = mockk(relaxed = true)
    }
}
