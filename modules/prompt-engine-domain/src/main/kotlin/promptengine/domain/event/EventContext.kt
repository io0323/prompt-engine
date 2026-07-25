package promptengine.domain.event

import java.time.Instant

/**
 * Domain Eventの発行に必要な、Aggregate自身では持ち得ない文脈情報
 * （誰が・いつ・どのリクエストか）をまとめたパラメータオブジェクト。
 */
data class EventContext(
    val actor: String,
    val traceId: String,
    val occurredAt: Instant,
)
