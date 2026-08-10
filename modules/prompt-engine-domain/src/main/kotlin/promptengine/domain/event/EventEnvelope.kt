package promptengine.domain.event

import java.time.Instant
import java.util.UUID

/**
 * 購読側（[EventSubscriber]）がBrokerから受け取るDomain Eventの封筒（ADR-0026決定1）。
 *
 * [DomainEvent]（発行側の型付き封筒）と同じ8項目を持つが、[payload]だけは型付きの`Any`ではなく
 * **JSON文字列**として保持する。購読側はTopic上を流れる任意のイベント種別を受け取るため、
 * 発行側の具象[DomainEvent]実装クラスへ逆シリアライズできるとは限らない（`AuditEngine`は
 * 6トピック全種を、まだ具象クラスの無いイベント種別も含めて受け取る）。個々の購読側が
 * 自分の関心のあるイベント種別についてのみ[payload]を解釈する。
 *
 * [payload]はSecretマスク済である（発行経路が[promptengine.domain.shared.SensitiveValue]を
 * 実値のままシリアライズしない契約、ADR-0026決定4）。購読側は追加のマスクを行わなくても
 * 保存してよいが、多層防御として保存直前にもサニタイズする実装を妨げない。
 */
data class EventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val actor: String,
    val traceId: String,
    val payload: String,
    val occurredAt: Instant,
)
