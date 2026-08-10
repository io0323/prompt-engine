package promptengine.infrastructure.subscriber

import promptengine.domain.audit.AuditLogEntry
import promptengine.domain.audit.AuditRepository
import promptengine.domain.event.EventEnvelope
import promptengine.domain.event.EventSubscriber
import promptengine.domain.event.EventTopic
import promptengine.infrastructure.masking.SecretMaskingJsonSanitizer
import java.util.UUID

/**
 * 設計書§14の全6トピックを購読し、届いた全イベントを`audit_logs`へ追記する
 * （設計書§14「購読先: ... Audit」、ADR-0026決定4）。
 *
 * ## Pipeline Stage 12（AuditStage）との違い
 * [AuditStage][promptengine.application.pipeline.AuditStage]はPipeline実行1回につき1件の
 * [AuditRecord][promptengine.domain.audit.AuditRecord]を`AuditRepository.append`で書く、
 * Pipeline専用の狭い経路（ADR-0015決定7）。本クラスは**イベント1件につき1行**を
 * [AuditRepository.record]（ADR-0017の一般形）で書く、独立した非同期経路。
 * どちらか一方の失敗が他方を巻き込まないよう、あえて別経路のままにしている（ADR-0026決定1）。
 *
 * ## Secretマスク（設計書§12 `audit_logs.payload` は「Secretマスク済」）
 * 保存する`payload`は[SecretMaskingJsonSanitizer]を必ず通す（ADR-0026決定4のマスク第2層）。
 * 第1層は発行側の[SensitiveValueMaskingModule][promptengine.infrastructure.masking.SensitiveValueMaskingModule]
 * で、[promptengine.domain.shared.SensitiveValue]型の値はOutboxへ書かれる時点で既に`"***"`に
 * なっている。本クラスは全6トピック・全イベント種別（まだ具象クラスが存在しないものを含む）を
 * 無差別に保存する立場上、型を経由しない生のSecretが将来混入する可能性を構造的に
 * 排除できないため、保存直前にもフィールド名ベースのredactを掛ける。
 *
 * ## 冪等性
 * [AuditLogEntry.eventId]を渡すことで`ON CONFLICT (event_id) DO NOTHING`（V13）が効き、
 * 同一イベントの再配信で監査行が二重にならない（ADR-0025決定8）。
 */
class AuditEngine(
    private val auditRepository: AuditRepository,
    private val sanitizer: SecretMaskingJsonSanitizer,
) : EventSubscriber {
    override val name: String = SUBSCRIBER_NAME

    /** 設計書§14の全6トピック。監査は「全イベント」が対象（設計書§14「購読先」列）。 */
    override val topics: Set<EventTopic> = EventTopic.entries.toSet()

    override fun handle(envelope: EventEnvelope) {
        auditRepository.record(
            AuditLogEntry(
                auditId = UUID.randomUUID(),
                aggregateType = envelope.aggregateType,
                aggregateId = envelope.aggregateId,
                // audit_logs.action にはイベント種別（過去形、設計書§4.6）を入れる。
                // CRUD経路（ADR-0017）が動詞を入れるのと同じ列だが、どちらも
                // 「何が起きたか」を表す文字列であり意味的に一貫する。
                action = envelope.eventType,
                actor = envelope.actor,
                payload = sanitizer.sanitize(envelope.payload),
                traceId = envelope.traceId,
                occurredAt = envelope.occurredAt,
                eventId = envelope.eventId,
            ),
        )
    }

    companion object {
        /** Brokerのconsumer group IDおよび`dead_letter_queue.subscriber_name`。 */
        const val SUBSCRIBER_NAME = "pe-audit-engine"
    }
}
