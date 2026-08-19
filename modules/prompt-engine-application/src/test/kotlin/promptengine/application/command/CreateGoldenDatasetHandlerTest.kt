package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import java.time.Instant

class CreateGoldenDatasetHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun handler(
        promptRepository: InMemoryPromptRepository,
        goldenDatasetRepository: InMemoryGoldenDatasetRepository,
    ) = CreateGoldenDatasetHandler(promptRepository, goldenDatasetRepository, PassthroughIdempotentCommandExecutor())

    private fun draftPrompt(): Prompt =
        Prompt.create(promptKey, NewPromptVersion(SemVer(1, 0, 0), PromptContent("body")), context).first

    private fun command(
        items: List<GoldenDatasetItemInput> =
            listOf(GoldenDatasetItemInput(mapOf("productName" to "widget"), emptyMap(), "expected", emptyMap())),
    ) = CreateGoldenDatasetCommand(promptKey, "smoke-test", "説明", items, "user:owner", "trace-1")

    @Test
    fun `実在するPromptKeyならGoldenDatasetを作成する`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(draftPrompt()) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository()

        val result = handler(promptRepository, goldenDatasetRepository).handle(command())

        result.promptKey shouldBe promptKey.value
        result.itemCount shouldBe 1
        val saved = goldenDatasetRepository.findById(result.datasetId)!!
        saved.items.single().parameters shouldBe mapOf("productName" to "widget")
    }

    @Test
    fun `存在しないPromptKeyはPromptVersionNotFoundExceptionを投げる`() {
        val handler = handler(InMemoryPromptRepository(), InMemoryGoldenDatasetRepository())

        shouldThrow<PromptVersionNotFoundException> { handler.handle(command()) }
    }

    @Test
    fun `itemsが空ならIllegalArgumentExceptionを投げる`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(draftPrompt()) }
        val handler = handler(promptRepository, InMemoryGoldenDatasetRepository())

        shouldThrow<IllegalArgumentException> { handler.handle(command(items = emptyList())) }
    }
}
