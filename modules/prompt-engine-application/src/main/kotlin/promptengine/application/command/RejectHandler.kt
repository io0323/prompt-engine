package promptengine.application.command

import promptengine.domain.event.EventContext
import promptengine.domain.governance.ReviewCaseRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Instant

/**
 * `POST /prompts/{key}/versions/{v}/reject`（設計書§13.1、InReview→Draft、comment必須）。
 *
 * `reject`は`approve`と異なりガード（必要承認数）を持たないため、`ReviewCase.reject`は
 * 呼ぶたびに必ず`PromptRejected`を返す。`Prompt`側の`reject`遷移も同一トランザクションで
 * 実行する（ADR-0032決定1）。`reviewId`解決方法は[ApproveHandler]と同じ。
 */
data class RejectCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val comment: String,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(key, semVer, actor)
}

data class RejectResult(val key: PromptKey, val semVer: SemVer)

class RejectHandler(
    private val promptRepository: PromptRepository,
    private val reviewCaseRepository: ReviewCaseRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: RejectCommand): RejectResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            RejectResult::class.java,
        ) {
            val reviewCase =
                reviewCaseRepository.findInReview(command.key, command.semVer)
                    ?: throw InvalidStateTransitionException("NoActiveReviewCase", "reject")

            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updatedReviewCase, event) = reviewCase.reject(command.actor, command.comment, eventContext)
            reviewCaseRepository.save(updatedReviewCase, listOf(event))

            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)
            promptRepository.save(prompt.reject(command.semVer))

            RejectResult(command.key, command.semVer)
        }
}
