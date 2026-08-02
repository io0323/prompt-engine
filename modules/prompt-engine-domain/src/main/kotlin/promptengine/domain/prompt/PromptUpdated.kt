package promptengine.domain.prompt

import java.time.Instant
import java.util.UUID

/**
 * メタデータ（name/category/description/tags）更新（設計書§14）。
 *
 * ADR-0020により、メタデータは`Prompt` Aggregateの外（`PromptMetadataRepository`）で
 * 扱うため、他の`PromptDomainEvent`と異なり`Prompt`の操作メソッドからは発行されない。
 * `PATCH /prompts/{key}`のコマンドハンドラ（P9b）が直接構築する。
 */
data class PromptUpdated(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val aggregateId: String,
    override val actor: String,
    override val traceId: String,
    override val payload: Payload,
) : PromptDomainEvent() {
    data class Payload(val promptKey: String)
}
