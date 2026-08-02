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

class DiscardHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)

    @Test
    fun `discards a Draft version`() {
        val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val handler = DiscardHandler(promptRepository, PassthroughIdempotentCommandExecutor())

        val result = handler.handle(DiscardCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))

        result.key shouldBe promptKey
        promptRepository.savedEvents.size shouldBe 1
    }

    @Test
    fun `throws when the prompt does not exist`() {
        val handler = DiscardHandler(InMemoryPromptRepository(), PassthroughIdempotentCommandExecutor())

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(DiscardCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
    }
}
