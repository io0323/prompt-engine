package promptengine.application.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.InMemoryGoldenDatasetRepository
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.benchmark.GoldenDatasetNotFoundException
import promptengine.domain.prompt.PromptKey
import java.util.UUID

class GetGoldenDatasetHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private fun handler(repository: InMemoryGoldenDatasetRepository) = GetGoldenDatasetHandler(repository)

    @Test
    fun `存在するdatasetIdはitemの内容込みでViewを返す`() {
        val item =
            GoldenDatasetItem(
                UUID.randomUUID(),
                mapOf("productName" to "widget"),
                mapOf("ctx" to mapOf("k" to "v")),
                "expected",
                mapOf("tag" to "smoke"),
            )
        val dataset = GoldenDataset.create(promptKey, "smoke-test", "説明", listOf(item))
        val repository = InMemoryGoldenDatasetRepository().apply { seed(dataset) }

        val view = handler(repository).handle(GetGoldenDatasetQuery(dataset.datasetId))

        view.datasetId shouldBe dataset.datasetId
        view.promptKey shouldBe promptKey.value
        view.name shouldBe "smoke-test"
        view.items shouldBe
            listOf(
                GoldenDatasetItemView(
                    itemId = item.itemId,
                    parameters = mapOf("productName" to "widget"),
                    context = mapOf("ctx" to mapOf("k" to "v")),
                    expectedOutput = "expected",
                    metadata = mapOf("tag" to "smoke"),
                ),
            )
    }

    @Test
    fun `存在しないdatasetIdはGoldenDatasetNotFoundExceptionを投げる`() {
        val handler = handler(InMemoryGoldenDatasetRepository())

        shouldThrow<GoldenDatasetNotFoundException> { handler.handle(GetGoldenDatasetQuery(UUID.randomUUID())) }
    }
}
