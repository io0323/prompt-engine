package promptengine.application.command

import promptengine.application.pipeline.ReviewValidationGate
import promptengine.domain.event.EventContext
import promptengine.domain.governance.ApprovalPolicy
import promptengine.domain.governance.ReviewCase
import promptengine.domain.governance.ReviewCaseRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Instant

/**
 * `POST /prompts/{key}/versions/{v}/submit-review`（設計書§13.1、Draft→InReview）。
 *
 * ガード「Validation合格」（設計書§2.5）は[ReviewValidationGate]（COMPILE_ONLYモードで
 * [promptengine.application.pipeline.PipelineOrchestrator]を呼ぶ）が評価する。
 * `assertValidationPassed`が例外を投げずに戻った時点でValidation合格が確定しているため、
 * `Prompt.submitForReview`へは常に`validationPassed=true`で渡す。
 *
 * `Prompt`と`ReviewCase`の両方を同一トランザクションで保存する（ADR-0032決定1）。
 * `ReviewCase`側の`required_approvals`は[approvalPolicy]の現在値をこの時点で複製する
 * （ADR-0032決定2、以後この`ReviewCase`には遡及しない）。
 */
data class SubmitReviewCommand(
    val key: PromptKey,
    val semVer: SemVer,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(key, semVer)
}

data class SubmitReviewResult(val key: PromptKey, val semVer: SemVer)

class SubmitReviewHandler(
    private val promptRepository: PromptRepository,
    private val reviewCaseRepository: ReviewCaseRepository,
    private val reviewValidationGate: ReviewValidationGate,
    private val approvalPolicy: ApprovalPolicy,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: SubmitReviewCommand): SubmitReviewResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            SubmitReviewResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.key)
                    ?: throw PromptVersionNotFoundException.forKey(command.key)

            reviewValidationGate.assertValidationPassed(command.key, command.semVer, command.traceId)

            val inReview = prompt.submitForReview(command.semVer, validationPassed = true)
            promptRepository.save(inReview)

            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (reviewCase, event) = ReviewCase.create(command.key, command.semVer, approvalPolicy, eventContext)
            reviewCaseRepository.save(reviewCase, listOf(event))

            SubmitReviewResult(command.key, command.semVer)
        }
}
