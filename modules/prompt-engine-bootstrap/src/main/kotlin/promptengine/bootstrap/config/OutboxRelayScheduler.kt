package promptengine.bootstrap.config

import org.springframework.scheduling.annotation.Scheduled
import promptengine.infrastructure.messaging.OutboxRelayer

/**
 * `event_bus_outbox`・既存`outbox`それぞれを独立したポーリングジョブで中継する
 * （ADR-0025決定2・5）。`@Component`による自己登録は行わない。具象クラスのDI結線は
 * `prompt-engine-bootstrap`のConfigurationクラスの`@Bean`定義でのみ行う
 * （CLAUDE.md「具象クラスのDI結線は prompt-engine-bootstrap のConfigurationクラス
 * でのみ行う」）ため、[OutboxRelayConfig.outboxRelayScheduler]が`@Profile("production")`
 * 配下で構築する（CodeRabbitレビュー指摘: 当初`@Component`+`@Profile`で自己登録して
 * いたが、この規約に反していた）。ポーリング間隔は
 * `promptengine.eventbus.relay.poll-interval-ms`（[OutboxRelayProperties]）。
 *
 * [OutboxRelayConfig.outboxRelayTaskScheduler]（プールサイズ2）が実行を担うため、
 * この2メソッドは互いをブロックしない（片方のBroker送信が遅延しても、もう片方の
 * ポーリングサイクルは独立して進む。CodeRabbitレビュー指摘）。
 */
class OutboxRelayScheduler(
    private val eventBusOutboxRelayer: OutboxRelayer,
    private val domainEventOutboxRelayer: OutboxRelayer,
) {
    @Scheduled(fixedDelayString = "\${promptengine.eventbus.relay.poll-interval-ms:750}")
    fun relayEventBusOutbox() {
        eventBusOutboxRelayer.relayOnce()
    }

    @Scheduled(fixedDelayString = "\${promptengine.eventbus.relay.poll-interval-ms:750}")
    fun relayDomainEventOutbox() {
        domainEventOutboxRelayer.relayOnce()
    }
}
