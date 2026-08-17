package promptengine.application.command

import promptengine.domain.event.EventContext
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.shared.IdempotentCommandExecutor
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** `POST /experiments/{id}/start`（設計書§13.1）。 */
data class StartExperimentCommand(
    val experimentId: UUID,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$experimentId"
}

data class StartExperimentResult(val experimentId: UUID)

/**
 * `Draft`→`Running`。同一PromptKeyに既に`Running`中のExperimentがあれば拒否する
 * （[promptengine.application.experiment.ExperimentVariantResolver]が「1Promptにつき
 * 同時に1件のRunning Experiment」を前提とするため、ADR-0034決定1のKDoc参照）。
 */
class StartExperimentHandler(
    private val experimentRepository: ExperimentRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: StartExperimentCommand): StartExperimentResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            StartExperimentResult::class.java,
        ) {
            val experiment =
                experimentRepository.findById(command.experimentId)
                    ?: throw ExperimentNotFoundException(command.experimentId)

            val alreadyRunning = experimentRepository.findActiveByPrompt(experiment.promptKey)
            require(alreadyRunning.isEmpty()) {
                "Prompt '${experiment.promptKey.value}' already has a running experiment: " +
                    alreadyRunning.first().experimentId
            }

            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updated, event) = experiment.start(eventContext)
            val saved = experimentRepository.save(updated, listOf(event))
            StartExperimentResult(saved.experimentId)
        }
}
