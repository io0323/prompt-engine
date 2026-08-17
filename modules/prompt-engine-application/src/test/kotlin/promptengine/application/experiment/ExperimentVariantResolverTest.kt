package promptengine.application.experiment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import promptengine.application.command.InMemoryExperimentRepository
import promptengine.application.command.InMemoryPromptRepository
import promptengine.domain.event.EventContext
import promptengine.domain.experiment.Experiment
import promptengine.domain.experiment.ExperimentType
import promptengine.domain.experiment.TrafficPolicy
import promptengine.domain.experiment.TrafficSplitStrategy
import promptengine.domain.experiment.Variant
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import promptengine.domain.prompt.VersionRef
import promptengine.domain.shared.Cost
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ExperimentVariantResolverTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))
    private val modelProfile = ModelProfile(TokenCount(1_000), "tokenizer", Cost(BigDecimal.ZERO))

    private fun baseRequest(variableResolution: PromptRequest = PromptRequest()) =
        PipelineRequest(
            promptKey = promptKey,
            versionRef = VersionRef.Latest,
            variableResolution = variableResolution,
            modelProfile = modelProfile,
            budget = TokenCount(1_000),
        )

    private fun approvedPrompt(): Prompt {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val inReview = created.submitForReview(semVer, validationPassed = true)
        return inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
    }

    private fun variant(
        name: String,
        weightPct: Int,
    ) = Variant(UUID.randomUUID(), name, semVer, weightPct)

    @Test
    fun `RunningなExperimentが無ければrequestをそのまま返す`() {
        val experimentRepository = InMemoryExperimentRepository()
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val strategy = mockk<TrafficSplitStrategy>()
        val resolver = ExperimentVariantResolver(experimentRepository, promptRepository, strategy)
        val request = baseRequest()

        val resolved = resolver.resolve(request)

        resolved shouldBe request
        resolved.preResolvedVersion.shouldBeNull()
    }

    @Test
    fun `RunningなExperimentがあればTrafficSplitStrategyが選んだVariantを解決する`() {
        val prompt = approvedPrompt()
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 100), variant("treatment", 0)),
                TrafficPolicy(),
            )
        val (running, _) = experiment.start(context)
        val experimentRepository = InMemoryExperimentRepository().apply { seed(running) }
        val promptRepository = InMemoryPromptRepository().apply { seed(prompt) }
        val strategy = mockk<TrafficSplitStrategy>()
        every { strategy.select(running, null) } returns running.variants.first { it.name == "control" }
        val resolver = ExperimentVariantResolver(experimentRepository, promptRepository, strategy)

        val resolved = resolver.resolve(baseRequest())

        resolved.preResolvedVersion?.experimentId shouldBe running.experimentId
        resolved.preResolvedVersion?.variantId shouldBe running.variants.first { it.name == "control" }.variantId
        resolved.preResolvedVersion?.promptVersion?.semVer shouldBe semVer
    }

    @Test
    fun `stickyKeyPathが設定されていればcontextDataから値を読みTrafficSplitStrategyへ渡す`() {
        val prompt = approvedPrompt()
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 100), variant("treatment", 0)),
                TrafficPolicy(stickyKeyPath = "user.id"),
            )
        val (running, _) = experiment.start(context)
        val experimentRepository = InMemoryExperimentRepository().apply { seed(running) }
        val promptRepository = InMemoryPromptRepository().apply { seed(prompt) }
        val strategy = mockk<TrafficSplitStrategy>()
        every { strategy.select(running, "user-42") } returns running.variants.first { it.name == "control" }
        val resolver = ExperimentVariantResolver(experimentRepository, promptRepository, strategy)
        val request = baseRequest(PromptRequest(contextData = mapOf("user" to mapOf("id" to "user-42"))))

        val resolved = resolver.resolve(request)

        resolved.preResolvedVersion?.variantId shouldBe running.variants.first { it.name == "control" }.variantId
    }

    @Test
    fun `Variantが参照するVersionがArchive等で使用不可ならPromptVersionStateNotAllowedExceptionを伝播させる`() {
        val prompt =
            approvedPrompt()
                .let { it.publish(semVer, allDependenciesPublished = true, context).first }
                .let { it.deprecate(semVer, null, context).first }
                .let { it.archive(semVer, referencingClientCount = 0, force = true, context).first }
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 100), variant("treatment", 0)),
                TrafficPolicy(),
            )
        val (running, _) = experiment.start(context)
        val experimentRepository = InMemoryExperimentRepository().apply { seed(running) }
        val promptRepository = InMemoryPromptRepository().apply { seed(prompt) }
        val strategy = mockk<TrafficSplitStrategy>()
        every { strategy.select(running, null) } returns running.variants.first { it.name == "control" }
        val resolver = ExperimentVariantResolver(experimentRepository, promptRepository, strategy)

        shouldThrow<PromptVersionStateNotAllowedException> { resolver.resolve(baseRequest()) }
    }

    /**
     * DB外部キー（`variants.version_id` → `prompt_versions` → `prompts`）が構造的に防ぐはずの
     * 状態だが、`PromptRepository`インターフェース自体はnull返却を許すため防御的にガードする
     * （ADR-0034）。Fakeで意図的にPromptを未登録のままにして、このガードを実際に踏む。
     */
    @Test
    fun `Experimentが参照するPromptが見つからなければ例外で失敗する`() {
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 100), variant("treatment", 0)),
                TrafficPolicy(),
            )
        val (running, _) = experiment.start(context)
        val experimentRepository = InMemoryExperimentRepository().apply { seed(running) }
        val promptRepository = InMemoryPromptRepository()
        val strategy = mockk<TrafficSplitStrategy>()
        every { strategy.select(running, null) } returns running.variants.first { it.name == "control" }
        val resolver = ExperimentVariantResolver(experimentRepository, promptRepository, strategy)

        shouldThrow<IllegalStateException> { resolver.resolve(baseRequest()) }
    }

    /**
     * Variantが参照するSemVerがPromptから削除されている（本来は起き得ないが型では防げない）
     * 場合の防御ガード（ADR-0034）。
     */
    @Test
    fun `Variantが参照するSemVerがPromptに存在しなければ例外で失敗する`() {
        val prompt = approvedPrompt()
        val experiment =
            Experiment.create(
                promptKey,
                ExperimentType.AB,
                listOf(variant("control", 100), variant("treatment", 0)),
                TrafficPolicy(),
            )
        val (running, _) = experiment.start(context)
        val experimentRepository = InMemoryExperimentRepository().apply { seed(running) }
        val promptRepository = InMemoryPromptRepository().apply { seed(prompt) }
        val strategy = mockk<TrafficSplitStrategy>()
        val phantomVariant = running.variants.first { it.name == "control" }.copy(promptVersionSemVer = SemVer(9, 9, 9))
        every { strategy.select(running, null) } returns phantomVariant
        val resolver = ExperimentVariantResolver(experimentRepository, promptRepository, strategy)

        shouldThrow<IllegalStateException> { resolver.resolve(baseRequest()) }
    }
}
