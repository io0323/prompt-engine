package promptengine.infrastructure.messaging

import org.slf4j.LoggerFactory
import promptengine.domain.event.EventTopicResolver
import java.time.Duration
import java.time.Instant

/**
 * Outbox → Broker中継エンジン（ADR-0025決定2・決定3）。[OutboxSource]でパラメータ化することで
 * `event_bus_outbox`・既存`outbox`のどちらに対しても同じクレーム/ディスパッチ/リトライロジックを
 * 再利用する。呼出元（`prompt-engine-bootstrap`の`@Scheduled`ジョブ）が[relayOnce]を
 * ポーリング間隔ごとに呼ぶ想定。
 *
 * 3フェーズ（ADR-0025決定3）:
 * 1. [OutboxSource.claimBatch]で未配信行をクレーム（短いトランザクション）
 * 2. [producer]でBrokerへ送信（DBトランザクションを開かない）
 * 3. 成否に応じて[OutboxSource.markDispatched]/[OutboxSource.markFailed]で確定（行単位の短いトランザクション）
 *
 * フェーズ3は[instanceId]によるフェンシング（`claimed_by`の一致確認、ADR-0025決定3）を
 * 前提に成立する。フェンシングが無ければ、claim_timeoutを超えて別インスタンスに再クレーム
 * された行を元のインスタンスが後から`markDispatched`/`markFailed`で上書きしてしまい、
 * 「クレームは一時点で1インスタンスにのみ属する」という3段階Claim方式の前提が破れる。
 */
class OutboxRelayer(
    private val source: OutboxSource,
    private val producer: EventProducer,
    private val envelopeCodec: EventEnvelopeCodec,
    private val instanceId: String,
    private val claimTimeout: Duration = DEFAULT_CLAIM_TIMEOUT,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    /** 1バッチ分の中継を実行し、Broker送信に成功した件数を返す。 */
    fun relayOnce(): Int {
        val claimed = source.claimBatch(instanceId, claimTimeout, batchSize)
        var dispatchedCount = 0
        claimed.forEach { envelope ->
            val topic = EventTopicResolver.resolve(envelope.eventType)
            val key = envelope.aggregateId.ifBlank { envelope.eventId.toString() }
            // メッセージ本文は payload 単体ではなく封筒全体のJSON（ADR-0026決定1）。
            // 購読側は eventType / actor / traceId / occurredAt を必要とするため。
            val sendResult =
                runCatching { producer.send(topic.topicName, key, envelopeCodec.encode(envelope), envelope.eventId) }
            if (sendResult.isSuccess) {
                dispatchedCount++
                val owned = source.markDispatched(envelope.outboxId, instanceId)
                if (!owned) logFencingLost(envelope, "markDispatched")
            } else {
                val nextAttemptAt = Instant.now().plus(backoffFor(envelope.attempts + 1))
                val owned = source.markFailed(envelope.outboxId, instanceId, nextAttemptAt)
                if (!owned) logFencingLost(envelope, "markFailed")
            }
        }
        return dispatchedCount
    }

    /**
     * claim_timeoutを超えて別インスタンスに再クレームされた行は、元の所有者が確定してはならない
     * （ADR-0025決定3、3段階Claim方式のフェンシング前提）。
     */
    private fun logFencingLost(
        envelope: OutboxEnvelope,
        phase: String,
    ) {
        logger.warn(
            "outbox_relay_fencing_lost outboxId={} eventId={} phase={} instanceId={}",
            envelope.outboxId,
            envelope.eventId,
            phase,
            instanceId,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OutboxRelayer::class.java)
        private val DEFAULT_CLAIM_TIMEOUT = Duration.ofSeconds(30)
        private const val DEFAULT_BATCH_SIZE = 50
        private val BACKOFF_BASE = Duration.ofSeconds(1)
        private val BACKOFF_CAP = Duration.ofMinutes(5)

        /**
         * 指数バックオフ（ADR-0025決定4）: `base * 2^(attemptNumber - 1)`を[BACKOFF_CAP]で
         * 上限クリップする。DLQは持たず、無期限に再試行し続ける（10bスコープ、ADR-0025決定4）。
         */
        internal fun backoffFor(attemptNumber: Int): Duration {
            val shift = (attemptNumber - 1).coerceIn(0, MAX_SHIFT)
            val computed = BACKOFF_BASE.multipliedBy(1L shl shift)
            return if (computed > BACKOFF_CAP) BACKOFF_CAP else computed
        }

        // 2^62相当まで許せば十分にBACKOFF_CAPへ張り付くため、Long左シフトのオーバーフローを避ける。
        private const val MAX_SHIFT = 40
    }
}
