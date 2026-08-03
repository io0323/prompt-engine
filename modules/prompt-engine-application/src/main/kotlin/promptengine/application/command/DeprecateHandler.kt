package promptengine.application.command

import promptengine.domain.event.EventContext
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Instant

/** `POST /prompts/{key}/versions/{v}/deprecate`（設計書§13.1、`Published→Deprecated`、ガードなし）。 */
data class DeprecateCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val recommendedReplacement: VersionRef? = null,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String =
        "${this::class.simpleName}:" +
            listOf(key, semVer, recommendedReplacement)
}

data class DeprecateResult(val key: PromptKey, val semVer: SemVer)

class DeprecateHandler(
    private val promptRepository: PromptRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: DeprecateCommand): DeprecateResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            DeprecateResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updated, event) = prompt.deprecate(command.semVer, command.recommendedReplacement, eventContext)
            val saved = promptRepository.save(updated, listOf(event))
            DeprecateResult(saved.key, command.semVer)
        }
}
