package promptengine.bootstrap.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.application.command.ApproveHandler
import promptengine.application.command.RejectHandler
import promptengine.application.command.SubmitReviewHandler
import promptengine.application.pipeline.PipelineOrchestrator
import promptengine.application.pipeline.ReviewValidationGate
import promptengine.domain.governance.ApprovalPolicy
import promptengine.domain.governance.ReviewCaseRepository
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.shared.IdempotentCommandExecutor

/**
 * Governanceコンテキスト（`ReviewCase`、設計書§4.1）のDI配線（ADR-0032）。
 * [CommandHandlersConfig]と同様、設計書§13.1のエンドポイントと1:1対応する。
 */
@Configuration
@EnableConfigurationProperties(ApprovalPolicyProperties::class)
class GovernanceConfig {
    @Bean
    fun approvalPolicy(properties: ApprovalPolicyProperties): ApprovalPolicy = properties.toApprovalPolicy()

    @Bean
    fun reviewValidationGate(
        pipelineOrchestrator: PipelineOrchestrator,
        modelProfile: ModelProfile,
    ): ReviewValidationGate = ReviewValidationGate(pipelineOrchestrator, modelProfile)

    @Bean
    fun submitReviewHandler(
        promptRepository: PromptRepository,
        reviewCaseRepository: ReviewCaseRepository,
        reviewValidationGate: ReviewValidationGate,
        approvalPolicy: ApprovalPolicy,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): SubmitReviewHandler =
        SubmitReviewHandler(
            promptRepository,
            reviewCaseRepository,
            reviewValidationGate,
            approvalPolicy,
            idempotentCommandExecutor,
        )

    @Bean
    fun approveHandler(
        promptRepository: PromptRepository,
        reviewCaseRepository: ReviewCaseRepository,
        approvalPolicy: ApprovalPolicy,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): ApproveHandler =
        ApproveHandler(
            promptRepository,
            reviewCaseRepository,
            approvalPolicy.allowSelfApproval,
            idempotentCommandExecutor,
        )

    @Bean
    fun rejectHandler(
        promptRepository: PromptRepository,
        reviewCaseRepository: ReviewCaseRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): RejectHandler = RejectHandler(promptRepository, reviewCaseRepository, idempotentCommandExecutor)
}
