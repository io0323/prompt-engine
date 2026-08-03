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

class GetVersionHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    @Test
    fun `一致するVersionを返す`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val handler = GetVersionHandler(promptRepository)

        handler.handle(GetVersionQuery(promptKey, semVer)).semVer shouldBe semVer
    }

    @Test
    fun `Versionが存在しなければ例外を投げる`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val handler = GetVersionHandler(promptRepository)

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(GetVersionQuery(promptKey, SemVer(9, 9, 9)))
        }
    }

    @Test
    fun `Promptが存在しなければ例外を投げる`() {
        val handler = GetVersionHandler(InMemoryPromptRepository())

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(GetVersionQuery(promptKey, semVer))
        }
    }
}
