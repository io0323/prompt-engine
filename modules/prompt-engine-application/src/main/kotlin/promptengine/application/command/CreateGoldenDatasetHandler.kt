package promptengine.application.command

import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.IdempotentCommandExecutor
import java.util.UUID

/** [CreateGoldenDatasetCommand.items]の1件。 */
data class GoldenDatasetItemInput(
    val parameters: Map<String, Any?>,
    val context: Map<String, Map<String, Any?>>,
    val expectedOutput: String?,
    val metadata: Map<String, String>,
)

/** `POST /datasets`（設計書§13.1、ADR-0035決定2）。 */
data class CreateGoldenDatasetCommand(
    val promptKey: PromptKey,
    val name: String,
    val description: String?,
    val items: List<GoldenDatasetItemInput>,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$promptKey:$name:${items.size}"
}

data class CreateGoldenDatasetResult(val datasetId: UUID, val promptKey: String, val itemCount: Int)

/**
 * Golden Dataset作成ハンドラ（ADR-0035決定2）。[promptKey]（`Prompt`単位、特定のVersionには
 * 従属しない）が実在することのみ検証する。`name`/`items`非空の検証は`GoldenDataset.create`
 * が担う。
 */
class CreateGoldenDatasetHandler(
    private val promptRepository: PromptRepository,
    private val goldenDatasetRepository: GoldenDatasetRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
) {
    fun handle(command: CreateGoldenDatasetCommand): CreateGoldenDatasetResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            CreateGoldenDatasetResult::class.java,
        ) {
            promptRepository.findByKey(command.promptKey)
                ?: throw PromptVersionNotFoundException.forKey(command.promptKey)

            val items =
                command.items.map { input ->
                    GoldenDatasetItem(
                        itemId = UUID.randomUUID(),
                        parameters = input.parameters,
                        context = input.context,
                        expectedOutput = input.expectedOutput,
                        metadata = input.metadata,
                    )
                }
            val dataset = GoldenDataset.create(command.promptKey, command.name, command.description, items)
            val saved = goldenDatasetRepository.save(dataset)
            CreateGoldenDatasetResult(saved.datasetId, saved.promptKey.value, saved.items.size)
        }
}
