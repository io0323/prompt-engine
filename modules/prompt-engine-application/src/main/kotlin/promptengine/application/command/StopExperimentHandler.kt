package promptengine.application.command

import promptengine.domain.event.EventContext
import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.shared.IdempotentCommandExecutor
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** `POST /experiments/{id}/stop`（設計書§13.1）。 */
data class StopExperimentCommand(
    val experimentId: UUID,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$experimentId"
}

data class StopExperimentResult(val experimentId: UUID)

/** `Running`→`Stopped`。 */
class StopExperimentHandler(
    private val experimentRepository: ExperimentRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(command: StopExperimentCommand): StopExperimentResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            StopExperimentResult::class.java,
        ) {
            val experiment =
                experimentRepository.findById(command.experimentId)
                    ?: throw ExperimentNotFoundException(command.experimentId)

            val eventContext =
                EventContext(actor = command.actor, traceId = command.traceId, occurredAt = Instant.now(clock))
            val (updated, event) = experiment.stop(eventContext)
            val saved = experimentRepository.save(updated, listOf(event))
            StopExperimentResult(saved.experimentId)
        }
}
