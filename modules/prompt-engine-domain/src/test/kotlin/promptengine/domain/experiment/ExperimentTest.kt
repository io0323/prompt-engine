package promptengine.domain.experiment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

/**
 * Experiment Aggregate のテスト（設計書§4.3 不変条件「配分合計=100%。Running中のVariant
 * 削除禁止」、ADR-0034）。
 */
class ExperimentTest {
    private val promptKey = PromptKey("support/faq-answer")
    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun variant(
        name: String,
        weightPct: Int,
        semVer: SemVer = SemVer(1, 0, 0),
    ) = Variant(UUID.randomUUID(), name, semVer, weightPct)

    private fun createExperiment(variants: List<Variant> = listOf(variant("control", 50), variant("treatment", 50))) =
        Experiment.create(promptKey, ExperimentType.AB, variants, TrafficPolicy())

    @Test
    fun `create は配分合計が100%ならDraft状態のExperimentを生成する`() {
        val experiment = createExperiment()

        experiment.status shouldBe ExperimentStatus.Draft
        experiment.promptKey shouldBe promptKey
        experiment.variants.map { it.name } shouldBe listOf("control", "treatment")
        experiment.winnerVariantId.shouldBeNull()
    }

    @Test
    fun `create は配分合計が100%でなければIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            createExperiment(listOf(variant("control", 40), variant("treatment", 50)))
        }
    }

    @Test
    fun `create はVariantが2未満ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            createExperiment(listOf(variant("control", 100)))
        }
    }

    @Test
    fun `create はVariant名が重複していればIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            createExperiment(listOf(variant("control", 50), variant("control", 50)))
        }
    }

    @Test
    fun `start はDraftからRunningへ遷移しExperimentStartedを発行する`() {
        val experiment = createExperiment()

        val (updated, event) = experiment.start(context)

        updated.status shouldBe ExperimentStatus.Running
        event.eventType shouldBe "ExperimentStarted"
        event.aggregateType shouldBe "Experiment"
        event.aggregateId shouldBe experiment.experimentId.toString()
        event.payload.promptKey shouldBe promptKey.value
        event.payload.experimentId shouldBe experiment.experimentId
    }

    @Test
    fun `start はDraft以外から呼ぶとInvalidStateTransitionExceptionを投げる`() {
        val (running, _) = createExperiment().start(context)

        shouldThrow<InvalidStateTransitionException> { running.start(context) }
    }

    @Test
    fun `stop はRunningからStoppedへ遷移しExperimentStoppedを発行する`() {
        val (running, _) = createExperiment().start(context)

        val (updated, event) = running.stop(context)

        updated.status shouldBe ExperimentStatus.Stopped
        event.eventType shouldBe "ExperimentStopped"
        event.payload.experimentId shouldBe running.experimentId
    }

    @Test
    fun `stop はDraftから呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { createExperiment().stop(context) }
    }

    @Test
    fun `declareWinner はRunning中に呼べ勝者を記録しExperimentWinnerDeclaredを発行する`() {
        val (running, _) = createExperiment().start(context)

        val (updated, event) = running.declareWinner("treatment", context)

        val treatment = running.variants.single { it.name == "treatment" }
        updated.winnerVariantId shouldBe treatment.variantId
        event.eventType shouldBe "ExperimentWinnerDeclared"
        event.payload.winnerVariantId shouldBe treatment.variantId
        event.payload.winnerVariantName shouldBe "treatment"
    }

    @Test
    fun `declareWinner はDraftから呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { createExperiment().declareWinner("treatment", context) }
    }

    @Test
    fun `declareWinner は存在しないVariant名を渡すとIllegalArgumentExceptionを投げる`() {
        val (running, _) = createExperiment().start(context)

        shouldThrow<IllegalArgumentException> { running.declareWinner("unknown", context) }
    }

    @Test
    fun `promote は勝者未確定のままだとNoWinnerDeclaredExceptionを投げる`() {
        val (running, _) = createExperiment().start(context)

        shouldThrow<NoWinnerDeclaredException> { running.promote(context) }
    }

    @Test
    fun `promote は勝者確定後にCompletedへ遷移しExperimentCompletedを発行する`() {
        val (running, _) = createExperiment().start(context)
        val (declared, _) = running.declareWinner("treatment", context)

        val (updated, event) = declared.promote(context)

        updated.status shouldBe ExperimentStatus.Completed
        event.eventType shouldBe "ExperimentCompleted"
        val treatment = running.variants.single { it.name == "treatment" }
        event.payload.winnerVariantId shouldBe treatment.variantId
        event.payload.promotedSemVer shouldBe treatment.promptVersionSemVer
    }

    @Test
    fun `updateTraffic はRunning中に重みだけを変更できる`() {
        val (running, _) = createExperiment().start(context)
        val newVariants =
            running.variants.map { if (it.name == "control") it.copy(weightPct = 20) else it.copy(weightPct = 80) }

        val updated = running.updateTraffic(newVariants)

        updated.variants.single { it.name == "control" }.weightPct shouldBe 20
        updated.variants.single { it.name == "treatment" }.weightPct shouldBe 80
    }

    @Test
    fun `updateTraffic はDraftから呼ぶとInvalidStateTransitionExceptionを投げる`() {
        val experiment = createExperiment()

        shouldThrow<InvalidStateTransitionException> {
            experiment.updateTraffic(experiment.variants.map { it.copy(weightPct = 50) })
        }
    }

    @Test
    fun `updateTraffic はVariant集合を変える 実質的な削除 追加 とIllegalArgumentExceptionを投げる`() {
        val (running, _) = createExperiment().start(context)
        val differentVariants = listOf(variant("control", 50), variant("challenger", 50))

        shouldThrow<IllegalArgumentException> { running.updateTraffic(differentVariants) }
    }

    @Test
    fun `updateTraffic は配分合計が100%でなければIllegalArgumentExceptionを投げる`() {
        val (running, _) = createExperiment().start(context)
        val badWeights = running.variants.mapIndexed { i, v -> if (i == 0) v.copy(weightPct = 10) else v }

        shouldThrow<IllegalArgumentException> { running.updateTraffic(badWeights) }
    }

    @Test
    fun `ExperimentNotFoundExceptionはexperimentIdをメッセージに含む`() {
        val experimentId = UUID.randomUUID()

        val ex = ExperimentNotFoundException(experimentId)

        ex.message shouldBe "Experiment not found: '$experimentId'"
    }

    @Test
    fun `TrafficPolicyはstickyKeyPathが空白のみならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { TrafficPolicy(stickyKeyPath = "   ") }
    }

    @Test
    fun `TrafficPolicyはstickyKeyPathが未設定ならnullのまま許容する`() {
        TrafficPolicy().stickyKeyPath shouldBe null
    }

    @Test
    fun `restore はPersistenceApi経由でMementoから復元する`() {
        val experiment = createExperiment()
        val memento =
            ExperimentMemento(
                experimentId = experiment.experimentId,
                promptKey = experiment.promptKey,
                type = experiment.type,
                status = experiment.status,
                variants = experiment.variants,
                trafficPolicy = experiment.trafficPolicy,
                winnerVariantId = experiment.winnerVariantId,
            )

        @OptIn(promptengine.domain.shared.PersistenceApi::class)
        val restored = Experiment.restore(memento)

        restored shouldBe experiment
        restored.winnerVariantId.shouldBeNull()
    }
}
