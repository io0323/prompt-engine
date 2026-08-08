package promptengine.bootstrap.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import promptengine.infrastructure.messaging.OutboxRelayer

/**
 * `event_bus_outbox`・既存`outbox`それぞれを独立したポーリングジョブで中継する
 * （ADR-0025決定2・5）。`production`プロファイルのみで動作する（[OutboxRelayConfig]参照）。
 * ポーリング間隔は`promptengine.eventbus.relay.poll-interval-ms`（[OutboxRelayProperties]）。
 *
 * [OutboxRelayConfig.outboxRelayTaskScheduler]（プールサイズ2）が実行を担うため、
 * この2メソッドは互いをブロックしない（片方のBroker送信が遅延しても、もう片方の
 * ポーリングサイクルは独立して進む。CodeRabbitレビュー指摘）。
 */
@Component
@Profile("production")
class OutboxRelayScheduler(
    @Qualifier("eventBusOutboxRelayer") private val eventBusOutboxRelayer: OutboxRelayer,
    @Qualifier("domainEventOutboxRelayer") private val domainEventOutboxRelayer: OutboxRelayer,
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
