package promptengine.application.command

import promptengine.domain.event.EventContext
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Instant

/** Draft破棄（設計書§2.5、`Draft→Archived`、ガードなし）。 */
data class DiscardCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = listOf(key, semVer).toString()
}

data class DiscardResult(val key: PromptKey, val semVer: SemVer)

class DiscardHandler(
    private val promptRepository: PromptRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: DiscardCommand): DiscardResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            DiscardResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updated, event) = prompt.discard(command.semVer, eventContext)
            val saved = promptRepository.save(updated, listOf(event))
            DiscardResult(saved.key, command.semVer)
        }
}
