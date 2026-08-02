package promptengine.application.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.InMemoryPromptRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import java.time.Instant

class DiffHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val v1 = SemVer(1, 0, 0)
    private val v2 = SemVer(1, 1, 0)
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    @Test
    fun `detects content changes between two versions`() {
        val (createdV1, _) = Prompt.create(promptKey, NewPromptVersion(v1, PromptContent("body v1")), context)
        val (withV2, _) = createdV1.newVersion(NewPromptVersion(v2, PromptContent("body v2")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(withV2) }
        val handler = DiffHandler(promptRepository)

        val diff = handler.handle(DiffQuery(promptKey, v1, v2))

        diff.contentChanged shouldBe true
        diff.from shouldBe v1
        diff.to shouldBe v2
    }

    @Test
    fun `throws when the from version does not exist`() {
        val (createdV1, _) = Prompt.create(promptKey, NewPromptVersion(v1, PromptContent("body v1")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(createdV1) }
        val handler = DiffHandler(promptRepository)

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(DiffQuery(promptKey, SemVer(9, 9, 9), v1))
        }
    }

    @Test
    fun `throws when the to version does not exist`() {
        val (createdV1, _) = Prompt.create(promptKey, NewPromptVersion(v1, PromptContent("body v1")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(createdV1) }
        val handler = DiffHandler(promptRepository)

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(DiffQuery(promptKey, v1, SemVer(9, 9, 9)))
        }
    }

    @Test
    fun `throws when the prompt does not exist`() {
        val handler = DiffHandler(InMemoryPromptRepository())

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(DiffQuery(promptKey, v1, v2))
        }
    }
}
