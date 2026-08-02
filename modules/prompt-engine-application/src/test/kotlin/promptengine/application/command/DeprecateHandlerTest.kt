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

class DeprecateHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)

    @Test
    fun `deprecates a Published version`() {
        val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val inReview = created.submitForReview(semVer, validationPassed = true)
        val approved = inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
        val (published, _) = approved.publish(semVer, allDependenciesPublished = true, context)
        val promptRepository = InMemoryPromptRepository().apply { seed(published) }
        val handler = DeprecateHandler(promptRepository, PassthroughIdempotentCommandExecutor())

        val result = handler.handle(DeprecateCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))

        result.key shouldBe promptKey
        promptRepository.savedEvents.size shouldBe 1
    }

    @Test
    fun `throws when the prompt does not exist`() {
        val handler = DeprecateHandler(InMemoryPromptRepository(), PassthroughIdempotentCommandExecutor())

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(DeprecateCommand(promptKey, semVer, actor = "tester", traceId = "trace-1"))
        }
    }
}
