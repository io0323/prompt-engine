package promptengine.application.query

import promptengine.domain.benchmark.GoldenDatasetNotFoundException
import promptengine.domain.benchmark.GoldenDatasetRepository
import java.util.UUID

data class GetGoldenDatasetQuery(val datasetId: UUID)

/** `GET /datasets/{id}`の1項目分（設計書§13.1、ADR-0035）。 */
data class GoldenDatasetItemView(
    val itemId: UUID,
    val parameters: Map<String, Any?>,
    val context: Map<String, Map<String, Any?>>,
    val expectedOutput: String?,
    val metadata: Map<String, String>,
)

data class GoldenDatasetView(
    val datasetId: UUID,
    val promptKey: String,
    val name: String,
    val description: String?,
    val items: List<GoldenDatasetItemView>,
)

/** `GET /datasets/{id}`（設計書§13.1、ADR-0035フェーズ(d)）。 */
class GetGoldenDatasetHandler(private val goldenDatasetRepository: GoldenDatasetRepository) {
    fun handle(query: GetGoldenDatasetQuery): GoldenDatasetView {
        val dataset =
            goldenDatasetRepository.findById(query.datasetId) ?: throw GoldenDatasetNotFoundException(query.datasetId)
        return GoldenDatasetView(
            datasetId = dataset.datasetId,
            promptKey = dataset.promptKey.value,
            name = dataset.name,
            description = dataset.description,
            items =
                dataset.items.map {
                    GoldenDatasetItemView(it.itemId, it.parameters, it.context, it.expectedOutput, it.metadata)
                },
        )
    }
}
