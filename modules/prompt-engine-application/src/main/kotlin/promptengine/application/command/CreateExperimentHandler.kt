package promptengine.application.command

import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentRepository
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.Variant
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import java.util.UUID

/** [CreateExperimentCommand.variants]の1件（設計書§13.1 `POST /experiments`）。 */
data class VariantInput(val name: String, val semVer: SemVer, val weightPct: Int)

/** `POST /experiments`（設計書§13.1、ADR-0034決定1・6）。 */
data class CreateExperimentCommand(
    val promptKey: PromptKey,
    val type: ExperimentType,
    val variants: List<VariantInput>,
    val stickyKeyPath: String?,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:" + listOf(promptKey, type, variants)
}

data class CreateExperimentResult(val experimentId: UUID, val promptKey: String)

/**
 * Experiment作成ハンドラ（ADR-0034決定1）。各Variantが参照する`PromptVersion`の状態が
 * `Approved`/`Published`/`Deprecated`のいずれかであることをここで検証する
 * （Draft/InReview参照は拒否）。この検証は作成時点のものであり、実験実行中の状態変化
 * （例えば`archive`）は捕まえない。実行直前の再検証は
 * [promptengine.domain.pipeline.ExperimentResolvedVersion.of]が別途行う（ADR-0034決定2b）。
 */
class CreateExperimentHandler(
    private val promptRepository: PromptRepository,
    private val experimentRepository: ExperimentRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
) {
    private val usableStates = setOf(LifecycleState.Approved, LifecycleState.Published, LifecycleState.Deprecated)

    fun handle(command: CreateExperimentCommand): CreateExperimentResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            CreateExperimentResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.promptKey)
                    ?: throw PromptVersionNotFoundException.forKey(command.promptKey)

            val variants =
                command.variants.map { input ->
                    val version =
                        prompt.versions.find { it.semVer == input.semVer }
                            ?: throw PromptVersionNotFoundException(input.semVer)
                    if (version.state !in usableStates) {
                        throw PromptVersionStateNotAllowedException(version.semVer, version.state)
                    }
                    Variant(UUID.randomUUID(), input.name, input.semVer, input.weightPct)
                }

            val experiment =
                Experiment.create(command.promptKey, command.type, variants, TrafficPolicy(command.stickyKeyPath))
            val saved = experimentRepository.save(experiment)
            CreateExperimentResult(saved.experimentId, saved.promptKey.value)
        }
}
