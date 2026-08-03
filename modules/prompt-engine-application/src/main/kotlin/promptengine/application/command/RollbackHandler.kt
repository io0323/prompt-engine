package promptengine.application.command

import promptengine.domain.event.EventContext
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Instant

/**
 * `POST /prompts/{key}/rollback`（設計書§13.1、`Published→Published`、過去Versionの再Publish）。
 * ガード「対象Versionが存在」は`PromptRepository.findByKey`で取得したAggregate自身の状態
 * （`Prompt.rollback`内部の`version()`）で評価する。
 */
data class RollbackCommand(
    val key: PromptKey,
    val targetSemVer: SemVer,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(key, targetSemVer)
}

data class RollbackResult(val key: PromptKey, val targetSemVer: SemVer)

class RollbackHandler(
    private val promptRepository: PromptRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: RollbackCommand): RollbackResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            RollbackResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updated, events) = prompt.rollback(command.targetSemVer, eventContext)
            val saved = promptRepository.save(updated, events)
            RollbackResult(saved.key, command.targetSemVer)
        }
}
