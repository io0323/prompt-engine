package promptengine.domain.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersion
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * [ExperimentResolvedVersion]のテスト（ADR-0034決定2b: Experiment実行直前の状態再検証）。
 *
 * 「Experiment作成時の検証だけでは、実行中にVersionがarchiveされた場合を捕まえられない」
 * という設計判断を、[ExperimentResolvedVersion.of]自体が毎回状態を検証することで
 * 固定する回帰テスト。
 */
@OptIn(ExperimentResolutionApi::class)
class ExperimentResolvedVersionTest {
    private val key = PromptKey("support/faq-answer")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun wrap(): String = "---\npe: \"1\"\nkind: prompt\nkey: ${key.value}\n---\nhello"

    private fun draft(): Prompt = Prompt.create(key, NewPromptVersion(semVer, PromptContent(wrap())), context).first

    private fun inReview(): Prompt = draft().submitForReview(semVer, validationPassed = true)

    private fun approved(): Prompt = inReview().approve(semVer, approvalCount = 1, requiredApprovalCount = 1)

    private fun published(): Prompt = approved().publish(semVer, allDependenciesPublished = true, context).first

    private fun deprecated(): Prompt = published().deprecate(semVer, null, context).first

    private fun archived(): Prompt = draft().discard(semVer, context).first

    private fun versionOf(prompt: Prompt): PromptVersion = prompt.versions.single { it.semVer == semVer }

    private fun assertAccepted(prompt: Prompt) {
        val version = versionOf(prompt)
        val resolved = ExperimentResolvedVersion.of(version, UUID.randomUUID(), UUID.randomUUID())
        resolved.promptVersion shouldBe version
    }

    private fun assertRejected(prompt: Prompt) {
        val version = versionOf(prompt)
        val ex =
            shouldThrow<PromptVersionStateNotAllowedException> {
                ExperimentResolvedVersion.of(version, UUID.randomUUID(), UUID.randomUUID())
            }
        ex.semVer shouldBe semVer
        ex.state shouldBe version.state
    }

    @Test
    fun `Approved状態のVersionは受理される`() = assertAccepted(approved())

    @Test
    fun `Published状態のVersionは受理される`() = assertAccepted(published())

    @Test
    fun `Deprecated状態のVersionは受理される`() = assertAccepted(deprecated())

    @Test
    fun `Draft状態のVersionはPromptVersionStateNotAllowedExceptionで拒否される`() = assertRejected(draft())

    @Test
    fun `InReview状態のVersionはPromptVersionStateNotAllowedExceptionで拒否される`() = assertRejected(inReview())

    @Test
    fun `Archived状態のVersionはPromptVersionStateNotAllowedExceptionで拒否される`() {
        val version = versionOf(archived())
        version.state shouldBe LifecycleState.Archived
        assertRejected(archived())
    }

    @Test
    fun `of が返す値はexperimentIdとvariantIdをそのまま保持する`() {
        val experimentId = UUID.randomUUID()
        val variantId = UUID.randomUUID()

        val resolved = ExperimentResolvedVersion.of(versionOf(approved()), experimentId, variantId)

        resolved.experimentId shouldBe experimentId
        resolved.variantId shouldBe variantId
    }
}
