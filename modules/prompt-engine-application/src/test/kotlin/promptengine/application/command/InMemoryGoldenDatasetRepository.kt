package promptengine.application.command

import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.domain.prompt.PromptKey
import java.util.UUID

/** テスト用フェイク。単純なMapベースの永続化（ADR-0035）。 */
class InMemoryGoldenDatasetRepository : GoldenDatasetRepository {
    private val store = mutableMapOf<UUID, GoldenDataset>()

    fun seed(dataset: GoldenDataset) {
        store[dataset.datasetId] = dataset
    }

    override fun findById(datasetId: UUID): GoldenDataset? = store[datasetId]

    override fun findByPromptKey(promptKey: PromptKey): List<GoldenDataset> =
        store.values.filter { it.promptKey == promptKey }

    override fun save(dataset: GoldenDataset): GoldenDataset {
        store[dataset.datasetId] = dataset
        return dataset
    }
}
