package promptengine.domain.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.PromptKey
import java.util.UUID

/**
 * GoldenDataset Aggregate のテスト（設計書§4.3 不変条件「item 1件以上」、ADR-0035）。
 */
class GoldenDatasetTest {
    private val promptKey = PromptKey("support/faq-answer")

    private fun item(expectedOutput: String? = "expected") =
        GoldenDatasetItem(
            itemId = UUID.randomUUID(),
            parameters = mapOf("productName" to "widget"),
            context = mapOf("user" to mapOf("locale" to "ja-JP")),
            expectedOutput = expectedOutput,
        )

    @Test
    fun `GoldenDatasetItem はmetadataを明示的に指定できる`() {
        val item =
            GoldenDatasetItem(
                itemId = UUID.randomUUID(),
                parameters = emptyMap(),
                context = emptyMap(),
                expectedOutput = null,
                metadata = mapOf("difficulty" to "hard"),
            )

        item.metadata shouldBe mapOf("difficulty" to "hard")
    }

    @Test
    fun `create はitemが1件以上ならGoldenDatasetを生成する`() {
        val dataset = GoldenDataset.create(promptKey, "smoke-test", "説明", listOf(item()))

        dataset.promptKey shouldBe promptKey
        dataset.name shouldBe "smoke-test"
        dataset.description shouldBe "説明"
        dataset.items.size shouldBe 1
    }

    @Test
    fun `create はitemが空ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            GoldenDataset.create(promptKey, "empty", null, emptyList())
        }
    }

    @Test
    fun `create は名前が空白ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            GoldenDataset.create(promptKey, "   ", null, listOf(item()))
        }
    }

    @Test
    fun `create はdescriptionがnullでも許容する`() {
        val dataset = GoldenDataset.create(promptKey, "no-description", null, listOf(item()))

        dataset.description shouldBe null
    }

    @Test
    fun `create はexpectedOutputがnullのitemも許容する`() {
        val dataset = GoldenDataset.create(promptKey, "consistency-only", null, listOf(item(expectedOutput = null)))

        dataset.items.single().expectedOutput shouldBe null
    }

    @Test
    fun `updateItems は新しいitem集合へ入れ替える`() {
        val dataset = GoldenDataset.create(promptKey, "before", null, listOf(item(), item()))
        val newItem = item(expectedOutput = "replaced")

        val updated = dataset.updateItems(listOf(newItem))

        updated.datasetId shouldBe dataset.datasetId
        updated.items shouldBe listOf(newItem)
    }

    @Test
    fun `updateItems はitemが空ならIllegalArgumentExceptionを投げる`() {
        val dataset = GoldenDataset.create(promptKey, "before", null, listOf(item()))

        shouldThrow<IllegalArgumentException> { dataset.updateItems(emptyList()) }
    }

    @Test
    fun `restore はMementoの内容をそのまま復元する`() {
        val memento =
            GoldenDatasetMemento(
                datasetId = UUID.randomUUID(),
                promptKey = promptKey,
                name = "restored",
                description = "説明",
                items = listOf(item()),
            )

        @OptIn(promptengine.domain.shared.PersistenceApi::class)
        val dataset = GoldenDataset.restore(memento)

        dataset.datasetId shouldBe memento.datasetId
        dataset.promptKey shouldBe memento.promptKey
        dataset.name shouldBe memento.name
        dataset.items shouldBe memento.items
    }
}
