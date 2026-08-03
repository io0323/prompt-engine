package promptengine.application.command

import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptMetadataRepository
import promptengine.domain.prompt.PromptUpdated
import promptengine.domain.shared.IdempotentCommandExecutor
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** `PATCH /prompts/{key}`（設計書§13.1、メタデータ更新）。 */
data class UpdatePromptMetadataCommand(
    val key: PromptKey,
    val name: String,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String =
        "${this::class.simpleName}:" + listOf(key, name, category, description, tags)
}

data class UpdatePromptMetadataResult(val key: PromptKey)

class UpdatePromptMetadataHandler(
    private val promptMetadataRepository: PromptMetadataRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: UpdatePromptMetadataCommand): UpdatePromptMetadataResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            UpdatePromptMetadataResult::class.java,
        ) {
            val metadata =
                PromptMetadata(command.key, command.name, command.category, command.description, command.tags)
            val event =
                PromptUpdated(
                    eventId = UUID.randomUUID(),
                    occurredAt = Instant.now(clock),
                    aggregateId = command.key.value,
                    actor = command.actor,
                    traceId = command.traceId,
                    payload = PromptUpdated.Payload(command.key.value),
                )
            promptMetadataRepository.upsert(metadata, listOf(event))
            UpdatePromptMetadataResult(command.key)
        }
}
