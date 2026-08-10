package promptengine.infrastructure.subscriber

import promptengine.domain.dlq.DeadLetterEntry
import promptengine.domain.dlq.DeadLetterQueueRepository
import promptengine.domain.event.EventSubscriber
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import promptengine.infrastructure.messaging.EventEnvelopeCodec
import java.time.Clock
import java.time.Instant

/**
 * 購読側で処理に失敗したメッセージをDLQ（`dead_letter_queue`）へ退避する
 * （Issue #37、ADR-0026決定2）。
 *
 * [KafkaSubscriberRunner]から退避の組み立て責務を切り出したもの。Runnerが
 * 「ポーリングとディスパッチ」だけに集中できるようにするためと、退避時のマスク
 * （[SecretMaskingJsonSanitizer]）とタイムスタンプ生成を1箇所に閉じるため。
 *
 * 退避する`payload`は必ず[SecretMaskingJsonSanitizer]を通す。DLQは運用者が中身を目視する
 * 前提のテーブルであり、`audit_logs`と同じ厳しさを要求する（ADR-0026決定2・決定4）。
 *
 * [failureReason]には例外クラス名のみを入れ、例外メッセージ本文は入れない
 * （`Slf4jAuditFailureHandler`が確立した方針: インフラ層由来の例外メッセージに
 * 接続情報等の秘密が混ざりうるため）。
 */
class SubscriberDeadLetterRecorder(
    private val deadLetterQueueRepository: DeadLetterQueueRepository,
    private val envelopeCodec: EventEnvelopeCodec,
    private val sanitizer: SecretMaskingJsonSanitizer,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * [rawMessage]（Brokerから受け取った本文）の処理が[cause]で失敗したことを退避する。
     *
     * 封筒としてデコードできない本文（＝[cause]がデコード失敗そのもの）の場合、`eventId`は
     * `null`、`eventType`は[KafkaSubscriberRunner.UNDECODABLE_EVENT_TYPE]になる。
     */
    fun record(
        subscriber: EventSubscriber,
        rawMessage: String,
        cause: Throwable,
    ) {
        val decoded = runCatching { envelopeCodec.decode(rawMessage) }.getOrNull()
        deadLetterQueueRepository.enqueue(
            DeadLetterEntry(
                eventId = decoded?.eventId,
                eventType = decoded?.eventType ?: KafkaSubscriberRunner.UNDECODABLE_EVENT_TYPE,
                subscriberName = subscriber.name,
                payload = sanitizer.sanitize(rawMessage),
                failureReason = cause.javaClass.simpleName,
                failedAt = Instant.now(clock),
            ),
        )
    }
}
