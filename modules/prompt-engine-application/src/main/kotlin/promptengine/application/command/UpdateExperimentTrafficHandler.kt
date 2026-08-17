package promptengine.application.command

import promptengine.domain.experiment.ExperimentNotFoundException
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.experiment.Variant
import promptengine.domain.shared.IdempotentCommandExecutor
import java.util.UUID

/** [UpdateExperimentTrafficCommand.weights]の1件（既存Variant名→新しい重み）。 */
data class VariantWeightInput(val name: String, val weightPct: Int)

/**
 * `PATCH /experiments/{id}/traffic`（設計書§13.1、ADR-0034決定6、Canary運用向けの
 * 新規エンドポイント）。Variant集合（名前の集合）は変更できない
 * （[promptengine.domain.experiment.Experiment.updateTraffic]参照）。
 */
data class UpdateExperimentTrafficCommand(
    val experimentId: UUID,
    val weights: List<VariantWeightInput>,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(experimentId, weights)
}

data class UpdateExperimentTrafficResult(val experimentId: UUID)

class UpdateExperimentTrafficHandler(
    private val experimentRepository: ExperimentRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
) {
    fun handle(command: UpdateExperimentTrafficCommand): UpdateExperimentTrafficResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            UpdateExperimentTrafficResult::class.java,
        ) {
            val experiment =
                experimentRepository.findById(command.experimentId)
                    ?: throw ExperimentNotFoundException(command.experimentId)

            val weightByName = command.weights.associate { it.name to it.weightPct }
            val newVariants =
                experiment.variants.map { variant ->
                    val newWeight =
                        weightByName[variant.name]
                            ?: throw IllegalArgumentException(
                                "Variant not found in traffic update: '${variant.name}'",
                            )
                    Variant(variant.variantId, variant.name, variant.promptVersionSemVer, newWeight)
                }

            val updated = experiment.updateTraffic(newVariants)
            val saved = experimentRepository.save(updated)
            UpdateExperimentTrafficResult(saved.experimentId)
        }
}
