package promptengine.infrastructure.messaging

import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * [EventProducer]の`kafka-clients`実装（ADR-0025決定9）。Redpanda等のKafkaワイヤプロトコル
 * 互換Brokerに対して同じクライアントで送信できる。
 *
 * [producer]のライフサイクル（構築・`close()`）は呼出元（`prompt-engine-bootstrap`の
 * Configuration、CLAUDE.md「具象クラスのDI結線はbootstrapでのみ行う」）が管理する。
 */
class KafkaEventProducer(
    private val producer: Producer<String, String>,
    private val sendTimeout: Duration = DEFAULT_SEND_TIMEOUT,
) : EventProducer {
    override fun send(
        topic: String,
        key: String,
        value: String,
    ) {
        producer.send(ProducerRecord(topic, key, value)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    companion object {
        private val DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10)
    }
}
