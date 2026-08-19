package promptengine.domain.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.SemVer
import java.util.UUID

/**
 * Benchmark Aggregate のテスト（設計書§4.3 不変条件「Target 1件以上」、ADR-0035）。
 */
class BenchmarkTest {
    private val promptKey = PromptKey("support/faq-answer")
    private val datasetId = UUID.randomUUID()

    private fun target(semVer: SemVer = SemVer(1, 0, 0)) = BenchmarkTarget(UUID.randomUUID(), semVer)

    private fun createBenchmark(
        targets: List<BenchmarkTarget> = listOf(target()),
        metrics: Set<BenchmarkMetricType> = setOf(BenchmarkMetricType.Accuracy),
        nRepetitions: Int = 3,
        temperature: Double? = null,
    ) = Benchmark.create(promptKey, datasetId, targets, metrics, nRepetitions, temperature)

    @Test
    fun `create はTargetが1件以上ならPending状態のBenchmarkを生成する`() {
        val benchmark = createBenchmark()

        benchmark.status shouldBe BenchmarkStatus.Pending
        benchmark.promptKey shouldBe promptKey
        benchmark.datasetId shouldBe datasetId
        benchmark.targets.size shouldBe 1
    }

    @Test
    fun `create はTargetが0件ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { createBenchmark(targets = emptyList()) }
    }

    @Test
    fun `create はTargetのVersionが重複していればIllegalArgumentExceptionを投げる`() {
        val duplicate = SemVer(1, 0, 0)
        shouldThrow<IllegalArgumentException> {
            createBenchmark(targets = listOf(target(duplicate), target(duplicate)))
        }
    }

    @Test
    fun `create はmetricsが空ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { createBenchmark(metrics = emptySet()) }
    }

    @Test
    fun `create はnRepetitionsが1未満ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> { createBenchmark(nRepetitions = 0) }
    }

    @Test
    fun `create は複数のTargetを許容する`() {
        val benchmark = createBenchmark(targets = listOf(target(SemVer(1, 0, 0)), target(SemVer(1, 1, 0))))

        benchmark.targets.size shouldBe 2
    }

    // ---- temperature（ADR-0035決定5）: Determinismを要求する場合、null/0.0以外は拒否する ----

    @Test
    fun `create はDeterminism要求時にtemperatureがnullなら許容する`() {
        val benchmark = createBenchmark(metrics = setOf(BenchmarkMetricType.Determinism), temperature = null)

        benchmark.temperature shouldBe null
    }

    @Test
    fun `create はDeterminism要求時にtemperatureが0_0なら許容する`() {
        val benchmark = createBenchmark(metrics = setOf(BenchmarkMetricType.Determinism), temperature = 0.0)

        benchmark.temperature shouldBe 0.0
    }

    @Test
    fun `create はDeterminism要求時にtemperatureが0_0以外ならIllegalArgumentExceptionを投げる`() {
        shouldThrow<IllegalArgumentException> {
            createBenchmark(metrics = setOf(BenchmarkMetricType.Determinism), temperature = 0.7)
        }
    }

    @Test
    fun `create はDeterminismを要求しなければ任意のtemperatureを許容する`() {
        val benchmark = createBenchmark(metrics = setOf(BenchmarkMetricType.Accuracy), temperature = 0.7)

        benchmark.temperature shouldBe 0.7
    }

    // ---- estimatedExecutionCount（ADR-0035決定5「事前コスト見積り」） ----

    @Test
    fun `estimatedExecutionCount はtargetCount times datasetSize times nRepetitions`() {
        val benchmark =
            createBenchmark(
                targets = listOf(target(SemVer(1, 0, 0)), target(SemVer(1, 1, 0))),
                nRepetitions = 3,
            )

        benchmark.estimatedExecutionCount(datasetSize = 5) shouldBe 2 * 5 * 3
    }

    @Test
    fun `start はPendingからRunningへ遷移する`() {
        val started = createBenchmark().start()

        started.status shouldBe BenchmarkStatus.Running
    }

    @Test
    fun `start はPending以外から呼ぶとInvalidStateTransitionExceptionを投げる`() {
        val running = createBenchmark().start()

        shouldThrow<InvalidStateTransitionException> { running.start() }
    }

    @Test
    fun `requestCancellation はRunningからCancellingへ遷移する`() {
        val cancelling = createBenchmark().start().requestCancellation()

        cancelling.status shouldBe BenchmarkStatus.Cancelling
    }

    @Test
    fun `requestCancellation はRunning以外から呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { createBenchmark().requestCancellation() }
    }

    @Test
    fun `cancel はCancellingからCancelledへ遷移する`() {
        val cancelled = createBenchmark().start().requestCancellation().cancel()

        cancelled.status shouldBe BenchmarkStatus.Cancelled
    }

    @Test
    fun `cancel はCancelling以外から呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { createBenchmark().start().cancel() }
    }

    @Test
    fun `complete はRunningからCompletedへ遷移する`() {
        val completed = createBenchmark().start().complete()

        completed.status shouldBe BenchmarkStatus.Completed
    }

    @Test
    fun `complete はRunning以外から呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { createBenchmark().complete() }
    }

    @Test
    fun `fail はRunningからFailedへ遷移する`() {
        val failed = createBenchmark().start().fail()

        failed.status shouldBe BenchmarkStatus.Failed
    }

    @Test
    fun `fail はCancellingからFailedへ遷移する`() {
        val failed = createBenchmark().start().requestCancellation().fail()

        failed.status shouldBe BenchmarkStatus.Failed
    }

    @Test
    fun `fail はPendingから呼ぶとInvalidStateTransitionExceptionを投げる`() {
        shouldThrow<InvalidStateTransitionException> { createBenchmark().fail() }
    }

    @Test
    fun `restore はMementoの内容をそのまま復元する`() {
        val memento =
            BenchmarkMemento(
                benchmarkId = UUID.randomUUID(),
                promptKey = promptKey,
                datasetId = datasetId,
                targets = listOf(target()),
                metrics = setOf(BenchmarkMetricType.Accuracy, BenchmarkMetricType.Consistency),
                nRepetitions = 5,
                status = BenchmarkStatus.Running,
                temperature = 0.3,
            )

        @OptIn(promptengine.domain.shared.PersistenceApi::class)
        val benchmark = Benchmark.restore(memento)

        benchmark.benchmarkId shouldBe memento.benchmarkId
        benchmark.status shouldBe BenchmarkStatus.Running
        benchmark.temperature shouldBe 0.3
        benchmark.metrics shouldBe memento.metrics
        benchmark.nRepetitions shouldBe 5
    }
}
