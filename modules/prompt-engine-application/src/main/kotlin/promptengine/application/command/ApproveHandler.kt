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
 * `POST /prompts/{key}/versions/{v}/approve`（設計書§13.1、InReview→Approved）。
 *
 * `ReviewCase.approve`が必要承認数に達したと判断した場合（戻り値の`PromptApproved`イベントが
 * 非null）のみ、同一トランザクション内で`Prompt.approve`を呼び`Prompt`側も保存する
 * （ADR-0032決定1、「承認されたのにApprovedになっていない瞬間」を作らないための
 * Aggregate横断の意図的な例外）。閾値未達の場合は`ReviewCase`のみ保存し、`Prompt`には触れない。
 *
 * `reviewId`はパスに含まれない（設計書§13.1）ため、`ReviewCaseRepository.findInReview`で
 * `(key, semVer)`から現在の`InReview`な`ReviewCase`を解決する。存在しない場合
 * （既にApproved/Rejected、またはPrompt側がそもそもInReviewでない）は
 * `InvalidStateTransitionException`（`Prompt.rollback`の`"NoPublishedVersion"`と同じ
 * 「関連state不在」の表現方法）を投げる。
 */
data class ApproveCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val comment: String?,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(key, semVer, actor)
}

data class ApproveResult(val key: PromptKey, val semVer: SemVer)

class ApproveHandler(
    private val promptRepository: PromptRepository,
    private val reviewCaseRepository: ReviewCaseRepository,
    private val allowSelfApproval: Boolean,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: ApproveCommand): ApproveResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            ApproveResult::class.java,
        ) {
            val reviewCase =
                reviewCaseRepository.findInReview(command.key, command.semVer)
                    ?: throw InvalidStateTransitionException("NoActiveReviewCase", "approve")

            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updatedReviewCase, event) =
                reviewCase.approve(command.actor, command.comment, allowSelfApproval, eventContext)
            reviewCaseRepository.save(updatedReviewCase, listOfNotNull(event))

            if (event != null) {
                confirmPromptApproval(
                    command.key,
                    command.semVer,
                    updatedReviewCase.approvalCount,
                    updatedReviewCase.requiredApprovals,
                )
            }

            ApproveResult(command.key, command.semVer)
        }

    private fun confirmPromptApproval(
        key: PromptKey,
        semVer: SemVer,
        approvalCount: Int,
        requiredApprovals: Int,
    ) {
        val prompt = promptRepository.findByKey(key) ?: throw PromptVersionNotFoundException.forKey(key)
        val approved = prompt.approve(semVer, approvalCount, requiredApprovals)
        promptRepository.save(approved)
    }
}
