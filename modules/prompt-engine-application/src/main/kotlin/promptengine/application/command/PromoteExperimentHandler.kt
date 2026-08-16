package promptengine.application.command

import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.experiment.ExperimentDomainEvent
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.fragment.FragmentRepository
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.template.TemplateRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * `POST /experiments/{id}/promote`（設計書§13.1、ADR-0034決定5）。[winnerVariantName]は
 * `GET /experiments/{id}/results`の統計判定結果を見た人が明示的に選ぶ（自動昇格はしない）。
 */
data class PromoteExperimentCommand(
    val experimentId: UUID,
    val winnerVariantName: String,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(experimentId, winnerVariantName)
}

data class PromoteExperimentResult(val experimentId: UUID, val promptKey: String, val promotedSemVer: String)

/**
 * [promptengine.domain.experiment.Experiment.declareWinner]と
 * [promptengine.domain.experiment.Experiment.promote]を呼んだ直後、勝者Variantの
 * `PromptVersion`を`Prompt.publish`させる（ADR-0032決定1と同じ、複数Aggregateを
 * 同一トランザクションで整合させるパターン、ADR-0034決定5・6）。
 *
 * コンストラクタ引数が多いのは`PublishHandler`と同じ協力者一式
 * （`DependencyPublicationChecker`の構成要素4つ）に`experimentRepository`が
 * 加わるため。意味のあるグルーピング単位が無く、無理に束ねるとパラメータオブジェクトの
 * 意図が読み取れなくなるため`LongParameterList`を明示的に許容する（`PipelineConfig`の
 * `pipelineStages`と同じ判断）。
 */
@Suppress("LongParameterList")
class PromoteExperimentHandler(
    private val experimentRepository: ExperimentRepository,
    private val promptRepository: PromptRepository,
    templateRepository: TemplateRepository,
    fragmentRepository: FragmentRepository,
    dependencyRepository: DependencyRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dependencyPublicationChecker =
        DependencyPublicationChecker(promptRepository, templateRepository, fragmentRepository, dependencyRepository)

    fun handle(command: PromoteExperimentCommand): PromoteExperimentResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            PromoteExperimentResult::class.java,
        ) {
            val experiment =
                experimentRepository.findById(command.experimentId)
                    ?: throw ExperimentNotFoundException(command.experimentId)

            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (declared, winnerDeclaredEvent) = experiment.declareWinner(command.winnerVariantName, eventContext)
            val (completed, completedEvent) = declared.promote(eventContext)
            val savedExperiment =
                experimentRepository.save(completed, listOf<ExperimentDomainEvent>(winnerDeclaredEvent, completedEvent))

            val winnerSemVer = completedEvent.payload.promotedSemVer
            val prompt =
                promptRepository.findByKey(experiment.promptKey)
                    ?: throw PromptVersionNotFoundException.forKey(experiment.promptKey)
            val winnerVersion =
                prompt.versions.find { it.semVer == winnerSemVer }
                    ?: throw PromptVersionNotFoundException(winnerSemVer)
            val allDependenciesPublished =
                dependencyPublicationChecker.allDependenciesPublished(experiment.promptKey, winnerVersion)
            val (publishedPrompt, publishEvents) =
                prompt.publish(winnerSemVer, allDependenciesPublished, eventContext)
            promptRepository.save(publishedPrompt, publishEvents)

            PromoteExperimentResult(savedExperiment.experimentId, experiment.promptKey.value, winnerSemVer.toString())
        }
}
