package promptengine.bootstrap.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.context.support.TestPropertySourceUtils
import org.springframework.transaction.support.TransactionTemplate
import promptengine.infrastructure.messaging.OutboxRelayer

/**
 * [OutboxRelayConfig]・[OutboxRelayScheduler]のDI配線を検証する（ADR-0025決定2・3・9）。
 *
 * `production`プロファイルの起動全体は[promptengine.bootstrap.ProductionProfileGuardTest]が
 * 検証するが、そちらは`FakeExecutionAdapter`のガードで必ず起動が失敗するため（Bean生成順序に
 * 依存し、決定的ではない）、`OutboxRelayConfig`のBean定義（2つの`OutboxSource`を
 * `@Qualifier`で区別し2つの`OutboxRelayer`へ正しく注入する部分、`OutboxRelayConfig.
 * outboxRelayScheduler`がその2つを`@Qualifier`で正しく受け取る部分）が実際に解決されることを、
 * 独立した最小コンテキストで直接確認する。`@Qualifier`の付け間違い・Bean名の衝突は
 * 単体テスト（各クラスの直接インスタンス化）では検出できない、DI配線固有の欠陥のため。
 * `OutboxRelayScheduler`は`@Component`で自己登録しない（CLAUDE.md「具象クラスのDI結線は
 * bootstrapのConfigurationクラスでのみ行う」）ため、`context.register()`には
 * `OutboxRelayConfig`のみを渡し、`OutboxRelayScheduler`自体はそのBean定義経由で解決する。
 *
 * `KafkaProducer`の構築はBrokerへの同期接続を行わない（バックグラウンドのメタデータ更新
 * スレッドが非同期にリトライするのみ）ため、実Brokerなしでも安全にBeanを構築できる。
 */
class OutboxRelayConfigWiringTest {
    @Test
    fun `production下でOutboxRelayConfigの全Beanと2つのOutboxRelayerが正しく配線される`() {
        val context = AnnotationConfigApplicationContext()
        context.environment.setActiveProfiles("production")
        // MessagingSupportConfig: P10bでBrokerのワイヤ形式を封筒JSONへ変えた際に
        // OutboxRelayerがEventEnvelopeCodecを要求するようになったため（ADR-0026決定1）。
        context.register(
            OutboxRelayConfig::class.java,
            MessagingSupportConfig::class.java,
            StubJdbcBeansConfig::class.java,
        )
        context.refresh()

        try {
            val eventBusRelayer = context.getBean("eventBusOutboxRelayer", OutboxRelayer::class.java)
            val domainEventRelayer = context.getBean("domainEventOutboxRelayer", OutboxRelayer::class.java)
            eventBusRelayer shouldNotBe domainEventRelayer

            val properties = context.getBean(OutboxRelayProperties::class.java)
            properties.pollIntervalMs shouldBe 750L
            properties.batchSize shouldBe 50
            properties.claimTimeoutSeconds shouldBe 30L

            // OutboxRelayConfig.outboxRelayScheduler Beanが例外無く解決できることが、
            // @Qualifier注入が正しく2つのOutboxRelayerへ解決されたことの証明になる。
            context.getBean(OutboxRelayScheduler::class.java) shouldNotBe null

            // 2つの@Scheduledジョブが単一スレッドで直列化しないことの配線面の保証
            // （CodeRabbitレビュー指摘）。プールサイズ1のままだと片方の遅延がもう片方を止める。
            val taskScheduler = context.getBean(ThreadPoolTaskScheduler::class.java)
            taskScheduler.poolSize shouldBe 2
        } finally {
            context.close()
        }
    }

    @Test
    fun `promptengine_scheduler_enabled=falseならOutboxRelayConfig自体が丸ごと無効化される`() {
        val context = AnnotationConfigApplicationContext()
        context.environment.setActiveProfiles("production")
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "promptengine.scheduler.enabled=false")
        context.register(
            OutboxRelayConfig::class.java,
            MessagingSupportConfig::class.java,
            StubJdbcBeansConfig::class.java,
        )
        context.refresh()

        try {
            shouldThrow<NoSuchBeanDefinitionException> {
                context.getBean(OutboxRelayScheduler::class.java)
            }
        } finally {
            context.close()
        }
    }

    @Configuration
    private class StubJdbcBeansConfig {
        @Bean
        fun jdbcTemplate(): NamedParameterJdbcTemplate = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)

        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()
    }
}
