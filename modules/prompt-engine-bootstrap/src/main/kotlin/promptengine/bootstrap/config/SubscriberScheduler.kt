package promptengine.bootstrap.config

import org.springframework.scheduling.annotation.Scheduled
import promptengine.infrastructure.subscriber.KafkaSubscriberRunner

/**
 * 5つの購読側（Audit / ExecutionLog / Evaluation / CacheInvalidation / SearchIndex）を
 * それぞれ独立したポーリングジョブで駆動する（ADR-0026決定1）。
 *
 * [OutboxRelayScheduler]と同じ形: `@Component`による自己登録は行わず、
 * [SubscriberConfig.subscriberScheduler]が`@Profile("production")`配下で構築する
 * （CLAUDE.md「具象クラスのDI結線はbootstrapのConfigurationクラスでのみ行う」）。
 *
 * [SubscriberConfig.subscriberTaskScheduler]（プールサイズ5）が実行を担うため、5ジョブは
 * 互いをブロックしない。`@Scheduled(fixedDelay)`は同一ジョブの実行が重ならないことを
 * 保証するため、各[KafkaSubscriberRunner]が持つ`KafkaConsumer`への同時アクセスも起きない
 * （`KafkaConsumer`はマルチスレッド同時アクセス不可、[KafkaSubscriberRunner]のKDoc参照）。
 */
class SubscriberScheduler(
    private val auditRunner: KafkaSubscriberRunner,
    private val executionLogRunner: KafkaSubscriberRunner,
    private val evaluationRunner: KafkaSubscriberRunner,
    private val cacheInvalidationRunner: KafkaSubscriberRunner,
    private val searchIndexRunner: KafkaSubscriberRunner,
) {
    @Scheduled(fixedDelayString = POLL_INTERVAL)
    fun pollAudit() {
        auditRunner.pollOnce()
    }

    @Scheduled(fixedDelayString = POLL_INTERVAL)
    fun pollExecutionLog() {
        executionLogRunner.pollOnce()
    }

    @Scheduled(fixedDelayString = POLL_INTERVAL)
    fun pollEvaluation() {
        evaluationRunner.pollOnce()
    }

    @Scheduled(fixedDelayString = POLL_INTERVAL)
    fun pollCacheInvalidation() {
        cacheInvalidationRunner.pollOnce()
    }

    @Scheduled(fixedDelayString = POLL_INTERVAL)
    fun pollSearchIndex() {
        searchIndexRunner.pollOnce()
    }

    private companion object {
        const val POLL_INTERVAL = "\${promptengine.eventbus.subscriber.poll-interval-ms:500}"
    }
}
