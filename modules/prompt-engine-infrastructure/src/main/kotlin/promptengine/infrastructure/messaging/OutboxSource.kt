package promptengine.infrastructure.messaging

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Outbox → Broker中継（[OutboxRelayer]）が対象とするOutbox実装を抽象化する
 * （ADR-0025決定2）。`event_bus_outbox`（自己完結）と既存`outbox`
 * （`domain_events`とのJOINで封筒データを取得する、ADR-0006）の2実装が、
 * 同じクレーム/ディスパッチ/リトライロジックを共有するために使う。
 */
interface OutboxSource {
    /**
     * 未配信かつ再試行時刻に達した行を、他インスタンスと衝突しない形で[batchSize]件まで
     * クレームする（`SELECT ... FOR UPDATE SKIP LOCKED`、ADR-0025決定3フェーズ1）。
     * [claimTimeout]より古い`claimed_at`を持つ行（クレームしたプロセスがクラッシュした
     * とみなせる行）も再クレーム対象に含む。
     */
    fun claimBatch(
        instanceId: String,
        claimTimeout: Duration,
        batchSize: Int,
    ): List<OutboxEnvelope>

    /** [outboxId]の行をBroker配信済みとして確定する（ADR-0025決定3フェーズ3）。 */
    fun markDispatched(outboxId: UUID)

    /**
     * [outboxId]の行を配信失敗として記録し、クレームを解放して[nextAttemptAt]以降に
     * 再クレーム可能にする（ADR-0025決定3フェーズ3・決定4）。
     */
    fun markFailed(
        outboxId: UUID,
        nextAttemptAt: Instant,
    )
}

/**
 * Broker配信に必要な封筒フィールド一式（[promptengine.domain.event.DomainEvent]と同じ8項目に、
 * 中継制御用の[outboxId]・[attempts]を加えたもの）。[payload]はDB上のJSON文字列をそのまま
 * 保持する（Broker送信時は逆シリアライズせずそのまま値として使う、ADR-0025決定6）。
 */
data class OutboxEnvelope(
    val outboxId: UUID,
    val eventId: UUID,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val actor: String,
    val traceId: String,
    val payload: String,
    val occurredAt: Instant,
    val attempts: Int,
)
