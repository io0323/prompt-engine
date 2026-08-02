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

class SetAliasHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    @Test
    fun `sets an alias to an existing version`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val aliasRepository = InMemoryPromptAliasRepository()
        val handler = SetAliasHandler(promptRepository, aliasRepository, PassthroughIdempotentCommandExecutor())

        val result = handler.handle(SetAliasCommand(promptKey, "stable", semVer))

        result.alias shouldBe "stable"
        aliasRepository.find(promptKey, "stable")?.semVer shouldBe semVer
    }

    @Test
    fun `rejects an alias pointing to a non-existent version`() {
        val (draft, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(draft) }
        val handler =
            SetAliasHandler(promptRepository, InMemoryPromptAliasRepository(), PassthroughIdempotentCommandExecutor())

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(SetAliasCommand(promptKey, "stable", SemVer(9, 9, 9)))
        }
    }

    @Test
    fun `throws when the prompt does not exist`() {
        val handler =
            SetAliasHandler(
                InMemoryPromptRepository(),
                InMemoryPromptAliasRepository(),
                PassthroughIdempotentCommandExecutor(),
            )

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(SetAliasCommand(promptKey, "stable", semVer))
        }
    }
}
