package promptengine.bootstrap.config

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import promptengine.infrastructure.messaging.OutboxRelayer

/**
 * [OutboxRelayConfig]・[OutboxRelayScheduler]のDI配線を検証する（ADR-0025決定2・3・9）。
 *
 * `production`プロファイルの起動全体は[promptengine.bootstrap.ProductionProfileGuardTest]が
 * 検証するが、そちらは`FakeExecutionAdapter`のガードで必ず起動が失敗するため（Bean生成順序に
 * 依存し、決定的ではない）、`OutboxRelayConfig`のBean定義（2つの`OutboxSource`を
 * `@Qualifier`で区別し2つの`OutboxRelayer`へ正しく注入する部分、`OutboxRelayScheduler`が
 * その2つを`@Qualifier`で正しく受け取る部分）が実際に解決されることを、独立した
 * 最小コンテキストで直接確認する。`@Qualifier`の付け間違い・Bean名の衝突は
 * 単体テスト（各クラスの直接インスタンス化）では検出できない、DI配線固有の欠陥のため。
 *
 * `KafkaProducer`の構築はBrokerへの同期接続を行わない（バックグラウンドのメタデータ更新
 * スレッドが非同期にリトライするのみ）ため、実Brokerなしでも安全にBeanを構築できる。
 */
class OutboxRelayConfigWiringTest {
    @Test
    fun `production下でOutboxRelayConfigの全Beanと2つのOutboxRelayerが正しく配線される`() {
        val context = AnnotationConfigApplicationContext()
        context.environment.setActiveProfiles("production")
        context.register(
            OutboxRelayConfig::class.java,
            OutboxRelayScheduler::class.java,
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

            // OutboxRelayScheduler自体が例外無く解決できることが、コンストラクタの
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

    @Configuration
    private class StubJdbcBeansConfig {
        @Bean
        fun jdbcTemplate(): NamedParameterJdbcTemplate = mockk(relaxed = true)

        @Bean
        fun transactionTemplate(): TransactionTemplate = mockk(relaxed = true)
    }
}
