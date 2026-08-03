package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.ArchiveRequiresForceException
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import java.time.Instant

/**
 * `archive`ガード「参照クライアントゼロ確認 or 強制フラグ」（設計書§2.5）。
 * P9bレビュー方針B: `execution_logs`未実装のためforce=trueのみ受け付ける（Issue #48）。
 */
class ArchiveHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "tester", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private class FakeDependencyRepository : DependencyRepository {
        override fun findOutbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun findOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
        ): List<DependencyEdge> = emptyList()

        override fun findInbound(promptKey: PromptKey): List<DependencyEdge> = emptyList()

        override fun replaceOutbound(
            promptKey: PromptKey,
            semVer: SemVer,
            edges: List<DependencyEdge>,
        ) = Unit
    }

    private fun deprecatedPrompt(): Prompt {
        val newVersion = NewPromptVersion(semVer = semVer, content = PromptContent("body"))
        val (created, _) = Prompt.create(promptKey, newVersion, context)
        val inReview = created.submitForReview(semVer, validationPassed = true)
        val approved = inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
        val (published, _) = approved.publish(semVer, allDependenciesPublished = true, context)
        val (deprecated, _) = published.deprecate(semVer, recommendedReplacement = null, context)
        return deprecated
    }

    @Test
    fun `force=falseのarchiveは参照クライアントを確認せず拒否される`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(deprecatedPrompt()) }
        val handler =
            ArchiveHandler(promptRepository, FakeDependencyRepository(), PassthroughIdempotentCommandExecutor())

        shouldThrow<ArchiveRequiresForceException> {
            handler.handle(ArchiveCommand(promptKey, semVer, force = false, actor = "tester", traceId = "trace-1"))
        }
        promptRepository.savedEvents shouldBe emptyList()
    }

    @Test
    fun `force=trueのarchiveは成功する`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(deprecatedPrompt()) }
        val handler =
            ArchiveHandler(promptRepository, FakeDependencyRepository(), PassthroughIdempotentCommandExecutor())

        val result =
            handler.handle(ArchiveCommand(promptKey, semVer, force = true, actor = "tester", traceId = "trace-1"))

        result.key shouldBe promptKey
        promptRepository.savedEvents.size shouldBe 1
    }

    @Test
    fun `force=trueでもPromptが存在しなければ例外を投げる`() {
        val handler =
            ArchiveHandler(
                InMemoryPromptRepository(),
                FakeDependencyRepository(),
                PassthroughIdempotentCommandExecutor(),
            )

        shouldThrow<PromptVersionNotFoundException> {
            handler.handle(ArchiveCommand(promptKey, semVer, force = true, actor = "tester", traceId = "trace-1"))
        }
    }
}
