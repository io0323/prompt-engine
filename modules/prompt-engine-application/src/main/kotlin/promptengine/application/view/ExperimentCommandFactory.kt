package promptengine.application.view

import promptengine.application.command.CreateExperimentCommand
import promptengine.application.command.PromoteExperimentCommand
import promptengine.application.command.StartExperimentCommand
import promptengine.application.command.StopExperimentCommand
import promptengine.application.command.UpdateExperimentTrafficCommand
import promptengine.application.command.VariantInput
import promptengine.application.command.VariantWeightInput
import promptengine.application.query.ExperimentResultsQuery
import promptengine.domain.experiment.ExperimentType
import java.util.UUID

/**
 * `ExperimentController`が使うCommand/Queryを構築する（[VersionCommandFactory]と同じ形、ADR-0034）。
 */
object ExperimentCommandFactory {
    fun createExperimentCommand(
        promptKey: String,
        type: String,
        variants: List<Triple<String, String, Int>>,
        stickyKeyPath: String?,
        meta: RequestMeta,
    ): CreateExperimentCommand =
        CreateExperimentCommand(
            promptKey = DomainValueFactory.promptKey(promptKey),
            type = ExperimentType.valueOf(type),
            variants =
                variants.map {
                        (name, semVer, weightPct) ->
                    VariantInput(name, DomainValueFactory.semVer(semVer), weightPct)
                },
            stickyKeyPath = stickyKeyPath,
            actor = meta.actor,
            traceId = meta.traceId,
            idempotencyKey = meta.idempotencyKey,
        )

    fun startExperimentCommand(
        experimentId: UUID,
        meta: RequestMeta,
    ): StartExperimentCommand = StartExperimentCommand(experimentId, meta.actor, meta.traceId, meta.idempotencyKey)

    fun stopExperimentCommand(
        experimentId: UUID,
        meta: RequestMeta,
    ): StopExperimentCommand = StopExperimentCommand(experimentId, meta.actor, meta.traceId, meta.idempotencyKey)

    fun updateExperimentTrafficCommand(
        experimentId: UUID,
        weights: List<Pair<String, Int>>,
        meta: RequestMeta,
    ): UpdateExperimentTrafficCommand =
        UpdateExperimentTrafficCommand(
            experimentId = experimentId,
            weights = weights.map { (name, weightPct) -> VariantWeightInput(name, weightPct) },
            actor = meta.actor,
            traceId = meta.traceId,
            idempotencyKey = meta.idempotencyKey,
        )

    fun promoteExperimentCommand(
        experimentId: UUID,
        winnerVariantName: String,
        meta: RequestMeta,
    ): PromoteExperimentCommand =
        PromoteExperimentCommand(experimentId, winnerVariantName, meta.actor, meta.traceId, meta.idempotencyKey)

    fun experimentResultsQuery(experimentId: UUID): ExperimentResultsQuery = ExperimentResultsQuery(experimentId)
}
