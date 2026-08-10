package promptengine.bootstrap

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import promptengine.bootstrap.config.AuditEventConfig
import promptengine.domain.dlq.DeadLetterQueueRepository
import promptengine.domain.event.EventBusAdapter
import promptengine.infrastructure.messaging.InMemoryEventBusAdapter
import promptengine.infrastructure.messaging.OutboxEventBusAdapter

/**
 * ADR-0025決定6の回帰防止テスト（`docs/prompts/p10a.md`「Also add/extend a guard test」要件）。
 *
 * P8〜P9c時点では`AuditEventConfig.eventBusAdapter`に`@Profile`ガードが無く、`production`
 * プロファイルは`InMemoryEventBusAdapter`（本番選択で起動時エラー、ADR-0015決定7）により
 * 常に起動できなかった。ADR-0025で`eventBusAdapterProduction`/`eventBusAdapterDefault`へ
 * 分岐したことで、この制約はなくなった（1つ目のテスト）。
 *
 * 一方、[InMemoryEventBusAdapter]自身の`init`ガード（`production`で[IllegalStateException]）は
 * `InMemoryEventBusAdapterTest`（`prompt-engine-infrastructure`）が単体では検証済みだが、
 * それだけでは「`AuditEventConfig`の`@Profile`分岐が将来壊れてもガードが機能し続けるか」を
 * 証明しない。2つ目のテストは、[ProductionProfileGuardTest]と同じ発想（実際にSpring
 * コンテキストを起動して失敗を確認する）で、`production`プロファイルで
 * `InMemoryEventBusAdapter`を選択するBean定義がもし存在したら起動が失敗することを、
 * `AuditEventConfig`とは独立した最小構成（[RogueEventBusAdapterConfig]）を使って直接確認する。
 */
class EventBusAdapterProductionProfileGuardTest {
    @Test
    fun `productionプロファイルではOutboxEventBusAdapterが選ばれInMemoryEventBusAdapterは生成されない`() {
        val context = AnnotationConfigApplicationContext()
        context.environment.setActiveProfiles("production")
        context.register(AuditEventConfig::class.java, StubJdbcBeansConfig::class.java)
        context.refresh()

        try {
            val eventBusAdapter = context.getBean(EventBusAdapter::class.java)
            eventBusAdapter.shouldBeInstanceOf<OutboxEventBusAdapter>()
        } finally {
            context.close()
        }
    }

    @Test
    fun `productionでInMemoryEventBusAdapterを選択するBean定義があれば起動が失敗する`() {
        val context = AnnotationConfigApplicationContext()
        context.environment.setActiveProfiles("production")
        context.register(RogueEventBusAdapterConfig::class.java)

        val thrown = shouldThrow<Exception> { context.refresh() }

        val guardCause =
            generateSequence<Throwable>(thrown) { it.cause }
                .filterIsInstance<IllegalStateException>()
                .firstOrNull { it.message?.contains(GUARD_MESSAGE_FRAGMENT) == true }

        withClue(
            "起動失敗の原因チェーンにInMemoryEventBusAdapterのガード" +
                "(IllegalStateException: '$GUARD_MESSAGE_FRAGMENT'を含む)が見つからなかった。" +
                "起動は別の理由で失敗している可能性がある: $thrown",
        ) {
            guardCause.shouldNotBeNull()
        }
    }

    /**
     * [AuditEventConfig]のBean定義を、実DB接続無しに評価するためのスタブ。本テストは
     * `@Profile`条件によりどの実装が選ばれるかのみを検証し、生成されたAdapterのメソッドは
     * 一切呼び出さないため、実接続を持たないモックで足りる。
     */
    @Configuration
    private class StubJdbcBeansConfig {
        @Bean
        fun jdbcTemplate(): NamedParameterJdbcTemplate = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)

        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()

        /**
         * P10bで`AuditEventConfig.auditFailureHandler`が実DLQ実装
         * （`DeadLetterQueueAuditFailureHandler`、Issue #37）へ変わり、この協力者を要求する
         * ようになった。本テストはAdapterの選択のみを検証するためモックで足りる。
         */
        @Bean
        fun deadLetterQueueRepository(): DeadLetterQueueRepository = mockk(relaxed = true)
    }

    /** 「`production`で`InMemoryEventBusAdapter`が選ばれる」という回帰そのものの最小再現。 */
    @Configuration
    private class RogueEventBusAdapterConfig {
        @Bean
        @Profile("production")
        fun eventBusAdapter(environment: Environment): EventBusAdapter =
            InMemoryEventBusAdapter(environment.activeProfiles.toSet())
    }

    companion object {
        private const val GUARD_MESSAGE_FRAGMENT =
            "InMemoryEventBusAdapter must not be selected under the 'production' profile"
    }
}
