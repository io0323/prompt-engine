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

class RollbackHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val v1 = SemVer(1, 0, 0)
    private val v2 = SemVer(2, 0, 0)

    @Test
    fun `rolls back to a Deprecated version`() {
        val context =
            EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
        val (createdV1, _) = Prompt.create(promptKey, NewPromptVersion(v1, PromptContent("body v1")), context)
        val approvedV1 =
            createdV1.submitForReview(
                v1,
                validationPassed = true,
            ).approve(v1, approvalCount = 1, requiredApprovalCount = 1)
        val (publishedV1, _) = approvedV1.publish(v1, allDependenciesPublished = true, context)
        val (withV2, _) = publishedV1.newVersion(NewPromptVersion(v2, PromptContent("body v2")), context)
        val approvedV2 =
            withV2.submitForReview(
                v2,
                validationPassed = true,
            ).approve(v2, approvalCount = 1, requiredApprovalCount = 1)
        // publishV2はv1を自動的にDeprecated(SUPERSEDED)へ遷移させる（ADR-0005）。
        val (withV2Published, _) = approvedV2.publish(v2, allDependenciesPublished = true, context)
        val promptRepository = InMemoryPromptRepository().apply { seed(withV2Published) }
        val handler = RollbackHandler(promptRepository, PassthroughIdempotentCommandExecutor())

        val result = handler.handle(RollbackCommand(promptKey, v1, actor = "tester", traceId = "trace-1"))

        result.targetSemVer shouldBe v1
        promptRepository.savedEvents.size shouldBe 2
    }

    @Test
    fun `throws when the prompt does not exist`() {
        val handler = RollbackHandler(InMemoryPromptRepository(), PassthroughIdempotentCommandExecutor())

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(RollbackCommand(promptKey, v1, actor = "tester", traceId = "trace-1"))
        }
    }
}
