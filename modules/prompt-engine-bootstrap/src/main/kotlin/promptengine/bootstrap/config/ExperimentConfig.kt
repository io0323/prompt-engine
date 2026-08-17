package promptengine.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import promptengine.application.command.CreateExperimentHandler
import promptengine.application.command.PromoteExperimentHandler
import promptengine.application.command.StartExperimentHandler
import promptengine.application.command.StopExperimentHandler
import promptengine.application.command.UpdateExperimentTrafficHandler
import promptengine.application.query.GetExperimentResultsHandler
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.evaluation.EvaluationRepository
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.experiment.PromotionService
import promptengine.domain.experiment.TrafficSplitStrategy
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.template.TemplateRepository
import promptengine.engine.experiment.PromotionServiceImpl
import promptengine.engine.experiment.TrafficSplitStrategyImpl

/**
 * Experimentコンテキスト（`Experiment`、設計書§4.1、ADR-0034）のDI配線。
 * [GovernanceConfig]と同様、設計書§13.1のエンドポイントと1:1対応する。
 */
@Configuration
class ExperimentConfig {
    @Bean
    fun trafficSplitStrategy(): TrafficSplitStrategy = TrafficSplitStrategyImpl()

    @Bean
    fun promotionService(): PromotionService = PromotionServiceImpl()

    @Bean
    fun createExperimentHandler(
        promptRepository: PromptRepository,
        experimentRepository: ExperimentRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): CreateExperimentHandler =
        CreateExperimentHandler(
            promptRepository,
            experimentRepository,
            idempotentCommandExecutor,
        )

    @Bean
    fun startExperimentHandler(
        experimentRepository: ExperimentRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): StartExperimentHandler = StartExperimentHandler(experimentRepository, idempotentCommandExecutor)

    @Bean
    fun stopExperimentHandler(
        experimentRepository: ExperimentRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): StopExperimentHandler = StopExperimentHandler(experimentRepository, idempotentCommandExecutor)

    @Bean
    fun updateExperimentTrafficHandler(
        experimentRepository: ExperimentRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): UpdateExperimentTrafficHandler = UpdateExperimentTrafficHandler(experimentRepository, idempotentCommandExecutor)

    @Suppress("LongParameterList")
    @Bean
    fun promoteExperimentHandler(
        experimentRepository: ExperimentRepository,
        promptRepository: PromptRepository,
        templateRepository: TemplateRepository,
        fragmentRepository: FragmentRepository,
        dependencyRepository: DependencyRepository,
        idempotentCommandExecutor: IdempotentCommandExecutor,
    ): PromoteExperimentHandler =
        PromoteExperimentHandler(
            experimentRepository,
            promptRepository,
            templateRepository,
            fragmentRepository,
            dependencyRepository,
            idempotentCommandExecutor,
        )

    @Bean
    fun getExperimentResultsHandler(
        experimentRepository: ExperimentRepository,
        evaluationRepository: EvaluationRepository,
        promotionService: PromotionService,
    ): GetExperimentResultsHandler =
        GetExperimentResultsHandler(experimentRepository, evaluationRepository, promotionService)
}
