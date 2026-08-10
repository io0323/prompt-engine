package promptengine.infrastructure.subscriber

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import promptengine.domain.event.EventSubscriber
import promptengine.infrastructure.messaging.EventEnvelopeCodec
import java.time.Duration

/**
 * 1つの[EventSubscriber]をKafka互換Brokerへ接続して駆動する（ADR-0026決定1）。
 *
 * `kafka-clients`の[Consumer]を直接使う（Spring Kafkaは本プロジェクトの依存に無く、
 * [promptengine.infrastructure.messaging.KafkaEventProducer]と同じ方針）。
 * ポーリングの周期起動は`prompt-engine-bootstrap`の`@Scheduled`ジョブが担い
 * （`OutboxRelayScheduler`と同じ形）、本クラスは1サイクル分の[pollOnce]だけを公開する。
 *
 * ## スレッド安全性
 * [Consumer]はマルチスレッドからの**同時**アクセスを許さない。`@Scheduled(fixedDelay)`は
 * 同一ジョブの実行が重ならないことを保証するため、購読側ごとに専用のRunner・専用の
 * [Consumer]を1つずつ持つ限り安全（実行スレッドがサイクルごとに変わること自体は
 * `kafka-clients`が許容する）。
 *
 * ## オフセットコミットと失敗の扱い
 * 自動コミットは使わず、バッチ処理後に[Consumer.commitSync]する。1件の処理が失敗した場合は
 * DLQへ退避したうえで**コミットは行う**（ADR-0026決定2）。退避済みのメッセージを再消費し
 * 続けると、その1件が後続の全メッセージの処理を永久に止めてしまう（poison pill）ため。
 * 再処理はDLQからの手動オペレーションで行う。
 */
class KafkaSubscriberRunner(
    private val subscriber: EventSubscriber,
    private val consumer: Consumer<String, String>,
    private val envelopeCodec: EventEnvelopeCodec,
    private val deadLetterRecorder: SubscriberDeadLetterRecorder,
    private val pollTimeout: Duration = DEFAULT_POLL_TIMEOUT,
) : AutoCloseable {
    private var subscribed = false

    /** 1サイクル分ポーリングし、[EventSubscriber.handle]まで到達した件数を返す。 */
    fun pollOnce(): Int {
        ensureSubscribed()
        val records = consumer.poll(pollTimeout)
        if (records.isEmpty) return 0
        var handled = 0
        records.forEach { record ->
            if (dispatch(record)) handled++
        }
        consumer.commitSync()
        return handled
    }

    private fun ensureSubscribed() {
        if (subscribed) return
        consumer.subscribe(subscriber.topics.map { it.topicName })
        subscribed = true
    }

    /** 1件を処理する。処理できたら`true`、DLQへ退避したら`false`。 */
    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(record: ConsumerRecord<String, String>): Boolean =
        try {
            subscriber.handle(envelopeCodec.decode(record.value()))
            true
        } catch (e: Exception) {
            // 1件の失敗でポーリングループ全体を落とさない（ADR-0026決定2）。
            deadLetterRecorder.record(subscriber, record.value(), e)
            logger.error(
                "subscriber_handle_failed subscriber={} topic={} partition={} offset={} cause={}",
                subscriber.name,
                record.topic(),
                record.partition(),
                record.offset(),
                e.javaClass.simpleName,
            )
            false
        }

    override fun close() {
        consumer.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(KafkaSubscriberRunner::class.java)
        private val DEFAULT_POLL_TIMEOUT = Duration.ofMillis(500)

        /** 封筒としてデコードできなかったメッセージの`dead_letter_queue.event_type`。 */
        const val UNDECODABLE_EVENT_TYPE = "UndecodableEnvelope"
    }
}
