package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.dependency.DependencyEdge
import promptengine.domain.dependency.DependencyRepository
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.ArchiveEligibility
import promptengine.domain.prompt.ArchiveEligibilityRepository
import promptengine.domain.prompt.ArchiveGuardSettings
import promptengine.domain.prompt.ArchiveRequiresForceException
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.shared.SemVer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * `archive`ガード「参照クライアントゼロ確認 or 強制フラグ」（設計書§2.5）。
 * P10bで`execution_logs`ベースのガード（Issue #48、ADR-0026決定5）へ移行した。
 * カットオーバー以前に作られたVersionは判断不能として従来通りforce専用のまま
 * （P9bで固定した「force=trueのみ受け付ける」契約はこの経路で維持される）。
 */
class ArchiveHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val now = Instant.parse("2026-08-09T00:00:00Z")
    private val cutoverAt = Instant.parse("2026-08-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
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

    /** 判定結果を固定で返しつつ、呼び出し引数を検証できるようにする。 */
    private class FakeArchiveEligibilityRepository(
        private val eligibility: ArchiveEligibility,
    ) : ArchiveEligibilityRepository {
        var invocations = 0
        var lastCutoverAt: Instant? = null
        var lastInactiveSince: Instant? = null

        override fun evaluate(
            key: PromptKey,
            semVer: SemVer,
            cutoverAt: Instant,
            inactiveSince: Instant,
        ): ArchiveEligibility {
            invocations++
            lastCutoverAt = cutoverAt
            lastInactiveSince = inactiveSince
            return eligibility
        }
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

    private fun handler(
        eligibilityRepository: ArchiveEligibilityRepository,
        promptRepository: InMemoryPromptRepository = InMemoryPromptRepository().apply { seed(deprecatedPrompt()) },
        inactivityThreshold: Duration = Duration.ofDays(90),
    ) = ArchiveHandler(
        promptRepository,
        FakeDependencyRepository(),
        PassthroughIdempotentCommandExecutor(),
        eligibilityRepository,
        ArchiveGuardSettings(cutoverAt, inactivityThreshold),
        clock,
    )

    private fun command(force: Boolean) =
        ArchiveCommand(promptKey, semVer, force = force, actor = "tester", traceId = "trace-1")

    @Test
    fun `直近に実行があるVersionはforce無しのarchiveを拒否する`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(deprecatedPrompt()) }
        val handler = handler(FakeArchiveEligibilityRepository(ArchiveEligibility.RecentlyExecuted), promptRepository)

        shouldThrow<ArchiveRequiresForceException> { handler.handle(command(force = false)) }
        promptRepository.savedEvents shouldBe emptyList()
    }

    @Test
    fun `直近に実行が無いVersionはforce無しでarchiveできる`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(deprecatedPrompt()) }
        val handler = handler(FakeArchiveEligibilityRepository(ArchiveEligibility.Inactive), promptRepository)

        val result = handler.handle(command(force = false))

        result.key shouldBe promptKey
        promptRepository.savedEvents.size shouldBe 1
    }

    @Test
    fun `カットオーバー以前に作られたVersionは判断不能としてforce無しのarchiveを拒否する`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(deprecatedPrompt()) }
        val handler = handler(FakeArchiveEligibilityRepository(ArchiveEligibility.PreCutover), promptRepository)

        shouldThrow<ArchiveRequiresForceException> { handler.handle(command(force = false)) }
        promptRepository.savedEvents shouldBe emptyList()
    }

    @Test
    fun `force無しでVersionが存在しない場合はPromptVersionNotFoundExceptionを投げる`() {
        val handler = handler(FakeArchiveEligibilityRepository(ArchiveEligibility.VersionNotFound))

        shouldThrow<PromptVersionNotFoundException> { handler.handle(command(force = false)) }
    }

    @Test
    fun `force=trueは判定結果によらず常に成功しガード自体を呼ばない`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(deprecatedPrompt()) }
        val eligibilityRepository = FakeArchiveEligibilityRepository(ArchiveEligibility.RecentlyExecuted)
        val handler = handler(eligibilityRepository, promptRepository)

        val result = handler.handle(command(force = true))

        result.key shouldBe promptKey
        promptRepository.savedEvents.size shouldBe 1
        eligibilityRepository.invocations shouldBe 0
    }

    @Test
    fun `force=trueでもPromptが存在しなければ例外を投げる`() {
        val handler =
            ArchiveHandler(
                InMemoryPromptRepository(),
                FakeDependencyRepository(),
                PassthroughIdempotentCommandExecutor(),
                FakeArchiveEligibilityRepository(ArchiveEligibility.Inactive),
                ArchiveGuardSettings(cutoverAt, Duration.ofDays(90)),
                clock,
            )

        shouldThrow<PromptVersionNotFoundException> { handler.handle(command(force = true)) }
    }

    @Test
    fun `判定窓の開始時刻は現在時刻から設定された無活動期間を引いた値になる`() {
        val eligibilityRepository = FakeArchiveEligibilityRepository(ArchiveEligibility.Inactive)
        val handler = handler(eligibilityRepository, inactivityThreshold = Duration.ofDays(30))

        handler.handle(command(force = false))

        eligibilityRepository.lastCutoverAt shouldBe cutoverAt
        eligibilityRepository.lastInactiveSince shouldBe now.minus(Duration.ofDays(30))
    }
}
