package promptengine.application.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.InMemoryPromptMetadataRepository
import promptengine.application.command.InMemoryPromptRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptMetadata
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import java.time.Instant

class GetPromptHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    @Test
    fun `returns metadata and versions for an existing prompt`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val metadataRepository =
            InMemoryPromptMetadataRepository().apply { upsert(PromptMetadata(promptKey, "挨拶")) }
        val handler = GetPromptHandler(promptRepository, metadataRepository)

        val result = handler.handle(GetPromptQuery(promptKey))

        result.metadata?.name shouldBe "挨拶"
        result.versions.map { it.semVer } shouldBe listOf(semVer)
    }

    @Test
    fun `throws when the prompt does not exist`() {
        val handler = GetPromptHandler(InMemoryPromptRepository(), InMemoryPromptMetadataRepository())

        shouldThrow<PromptVersionNotFoundException> { handler.handle(GetPromptQuery(promptKey)) }
    }
}
