package promptengine.infrastructure.benchmark

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.BenchmarkScoringRule
import promptengine.domain.benchmark.BenchmarkStatus
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.benchmark.ClaimedBenchmarkItem
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.domain.execution.ExecutionOutcome
import promptengine.domain.execution.RawResponse
import promptengine.domain.execution.Usage
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.parsing.ParsedOutput
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineRunner
import promptengine.domain.prompt.PromptKey
import promptengine.domain.render.OutputFormat
import promptengine.domain.shared.Cost
import promptengine.domain.shared.LatencyMs
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.SensitiveValue
import promptengine.domain.shared.TokenCount
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

/**
 * [BenchmarkWorker]の単体テスト（ADR-0035決定3）。[BenchmarkRepository]/[GoldenDatasetRepository]/
 * [BenchmarkItemResultRepository]/[PipelineRunner]をモックし、正常系はTestcontainers統合テスト
 * （`BenchmarkWorkerIntegrationTest`）が担う一方、ここでは`?: error(...)`の防御分岐
 * （データ不整合時の異常系）を決定的に踏む。`OutboxRelayerTest`と同じ構成。
 */
class BenchmarkWorkerTest {
    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    private val promptKey = PromptKey("support/faq")
    private val datasetId = UUID.randomUUID()
    private val modelProfile =
        ModelProfile(maxContextTokens = TokenCount(1_000), tokenizerId = "test", costPerToken = Cost(BigDecimal.ZERO))
    private val scoringRules = listOf(FixedScoringRule("Accuracy", BigDecimal.ONE))

    @BeforeEach
    fun setUp() {
        logger = LoggerFactory.getLogger(BenchmarkWorker::class.java) as Logger
        logAppender = ListAppender()
        logAppender.start()
        logger.addAppender(logAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(logAppender)
    }

    private class FixedScoringRule(
        override val metricType: String,
        private val score: BigDecimal,
    ) : BenchmarkScoringRule {
        override fun score(
            actualOutputs: List<String>,
            expectedOutput: String?,
        ): BigDecimal = score
    }

    private fun target(targetId: UUID = UUID.randomUUID()) = BenchmarkTarget(targetId, SemVer(1, 0, 0))

    private fun item(itemId: UUID = UUID.randomUUID()) =
        GoldenDatasetItem(itemId = itemId, parameters = emptyMap(), context = emptyMap(), expectedOutput = "expected")

    private fun pendingBenchmark(targets: List<BenchmarkTarget> = listOf(target())): Benchmark =
        Benchmark.create(promptKey, datasetId, targets, setOf(BenchmarkMetricType.Accuracy), nRepetitions = 1)

    private fun successfulPipelineRunner(): PipelineRunner {
        val runner = mockk<PipelineRunner>()
        every { runner.run(any(), any(), any()) } answers {
            val request = firstArg<promptengine.domain.pipeline.PipelineRequest>()
            PipelineContext(
                request = request,
                mode = secondArg(),
                traceId = thirdArg(),
                executionOutcome =
                    ExecutionOutcome(
                        parsedOutput = ParsedOutput(OutputFormat.TEXT, raw = "out"),
                        attempts =
                            listOf(
                                RawResponse(
                                    SensitiveValue.of("out"),
                                    Usage(TokenCount(1), TokenCount(1)),
                                    LatencyMs(1),
                                ),
                            ),
                    ),
            )
        }
        return runner
    }

    private fun worker(
        benchmarkRepository: BenchmarkRepository,
        goldenDatasetRepository: GoldenDatasetRepository = mockk(),
        itemResultRepository: BenchmarkItemResultRepository = mockk(relaxed = true),
        pipelineRunner: PipelineRunner = successfulPipelineRunner(),
    ) = BenchmarkWorker(
        benchmarkRepository,
        goldenDatasetRepository,
        itemResultRepository,
        pipelineRunner,
        scoringRules,
        modelProfile,
        instanceId = "worker-1",
        claimTimeout = Duration.ofSeconds(30),
        batchSize = 20,
        executionTimeoutMs = 5_000,
    )

    // ---- pickUpPendingBenchmarks ----

    @Test
    fun `Pending取得時にGoldenDatasetが見つからなければfailへ遷移しWARNログを残す`() {
        val benchmark = pendingBenchmark()
        val benchmarkRepository = mockk<BenchmarkRepository>(relaxed = true)
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Pending) } returns listOf(benchmark)
        every { benchmarkRepository.save(any()) } answers { firstArg() }
        val goldenDatasetRepository = mockk<GoldenDatasetRepository>()
        every { goldenDatasetRepository.findById(datasetId) } returns null
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)

        worker(benchmarkRepository, goldenDatasetRepository, itemResultRepository).runOnce()

        verify { benchmarkRepository.save(match { it.status == BenchmarkStatus.Failed }) }
        verify(exactly = 0) { itemResultRepository.materialize(any()) }
        logAppender.list.any { it.formattedMessage.contains("benchmark_pickup_failed") } shouldBe true
    }

    @Test
    fun `Pending取得時にfailへの遷移保存自体が失敗してもクラッシュしない`() {
        // materialize失敗（GoldenDataset not found）に加え、その後始末のsave(started.fail())も
        // 失敗する（例: 別ワーカーとの競合）二重失敗ケース。runCatchingで包んでいるため
        // クラッシュせず次のBenchmarkへ進む（KDoc参照）。
        val benchmark = pendingBenchmark()
        val benchmarkRepository = mockk<BenchmarkRepository>(relaxed = true)
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Pending) } returns listOf(benchmark)
        every { benchmarkRepository.save(match { it.status == BenchmarkStatus.Running }) } answers { firstArg() }
        every { benchmarkRepository.save(match { it.status == BenchmarkStatus.Failed }) } throws
            IllegalStateException("concurrent update")
        val goldenDatasetRepository = mockk<GoldenDatasetRepository>()
        every { goldenDatasetRepository.findById(datasetId) } returns null
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)

        worker(benchmarkRepository, goldenDatasetRepository, itemResultRepository).runOnce()

        logAppender.list.any { it.formattedMessage.contains("benchmark_pickup_failed") } shouldBe true
    }

    @Test
    fun `Pending取得時にstart自体が失敗すれば以降の処理を行わずスキップする`() {
        // findByStatus(Pending)が返すBenchmarkは常にPending状態だが、start()後のsaveが
        // （他ワーカーとの競合等で）例外を投げるケースを模す。benchmarkはメモリ上まだ
        // Pendingのままのためfail()を呼べず、単にスキップする（KDoc参照）。
        val benchmark = pendingBenchmark()
        val benchmarkRepository = mockk<BenchmarkRepository>()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Pending) } returns listOf(benchmark)
        every { benchmarkRepository.save(any()) } throws IllegalStateException("concurrent update")
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Cancelling) } returns emptyList()
        val goldenDatasetRepository = mockk<GoldenDatasetRepository>()
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns emptyList()

        worker(benchmarkRepository, goldenDatasetRepository, itemResultRepository).runOnce()

        verify(exactly = 0) { goldenDatasetRepository.findById(any()) }
        verify(exactly = 0) { itemResultRepository.materialize(any()) }
    }

    // ---- finalizeCancellingBenchmarks ----

    @Test
    fun `Cancelling取得時にcancel保存が失敗してもクラッシュせずWARNログを残す`() {
        val cancelling = pendingBenchmark().start().requestCancellation()
        val benchmarkRepository = mockk<BenchmarkRepository>()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Pending) } returns emptyList()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Cancelling) } returns listOf(cancelling)
        every { benchmarkRepository.save(any()) } throws IllegalStateException("concurrent update")
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns emptyList()

        worker(benchmarkRepository, itemResultRepository = itemResultRepository).runOnce()

        logAppender.list.any { it.formattedMessage.contains("benchmark_cancel_finalize_failed") } shouldBe true
    }

    // ---- processItem: 防御分岐（データ不整合時）----

    private fun runningBenchmarkSetup(target: BenchmarkTarget = target()): Pair<BenchmarkRepository, UUID> {
        val benchmark = pendingBenchmark(listOf(target)).start()
        val benchmarkRepository = mockk<BenchmarkRepository>()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Pending) } returns emptyList()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Cancelling) } returns emptyList()
        every { benchmarkRepository.findById(benchmark.benchmarkId) } returns benchmark
        every { benchmarkRepository.save(any()) } answers { firstArg() }
        return benchmarkRepository to benchmark.benchmarkId
    }

    private fun claimed(
        benchmarkId: UUID,
        targetId: UUID,
        itemId: UUID = UUID.randomUUID(),
    ) = ClaimedBenchmarkItem(
        resultId = UUID.randomUUID(),
        benchmarkId = benchmarkId,
        targetId = targetId,
        itemId = itemId,
    )

    @Test
    fun `processItemでBenchmarkが見つからなければmarkFailedを呼ぶ`() {
        val benchmarkRepository = mockk<BenchmarkRepository>()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Pending) } returns emptyList()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Cancelling) } returns emptyList()
        val missingBenchmarkId = UUID.randomUUID()
        every { benchmarkRepository.findById(missingBenchmarkId) } returns null
        val claimedItem = claimed(missingBenchmarkId, UUID.randomUUID())
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns listOf(claimedItem)

        worker(benchmarkRepository, itemResultRepository = itemResultRepository).runOnce()

        verify {
            itemResultRepository.markFailed(
                claimedItem.resultId,
                "worker-1",
                match { it.contains("Benchmark not found") },
            )
        }
    }

    @Test
    fun `processItemでBenchmarkTargetが見つからなければmarkFailedを呼ぶ`() {
        val (benchmarkRepository, benchmarkId) = runningBenchmarkSetup()
        val claimedItem = claimed(benchmarkId, targetId = UUID.randomUUID())
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns listOf(claimedItem)

        worker(benchmarkRepository, itemResultRepository = itemResultRepository).runOnce()

        verify {
            itemResultRepository.markFailed(
                claimedItem.resultId,
                "worker-1",
                match { it.contains("BenchmarkTarget not found") },
            )
        }
    }

    @Test
    fun `processItemでGoldenDatasetが見つからなければmarkFailedを呼ぶ`() {
        val benchmarkTarget = target()
        val (benchmarkRepository, benchmarkId) = runningBenchmarkSetup(benchmarkTarget)
        val goldenDatasetRepository = mockk<GoldenDatasetRepository>()
        every { goldenDatasetRepository.findById(datasetId) } returns null
        val claimedItem = claimed(benchmarkId, benchmarkTarget.targetId)
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns listOf(claimedItem)

        worker(benchmarkRepository, goldenDatasetRepository, itemResultRepository).runOnce()

        verify {
            itemResultRepository.markFailed(
                claimedItem.resultId,
                "worker-1",
                match { it.contains("GoldenDataset not found") },
            )
        }
    }

    @Test
    fun `processItemでGoldenDatasetItemが見つからなければmarkFailedを呼ぶ`() {
        val benchmarkTarget = target()
        val (benchmarkRepository, benchmarkId) = runningBenchmarkSetup(benchmarkTarget)
        val dataset = GoldenDataset.create(promptKey, "dataset", null, listOf(item()))
        val goldenDatasetRepository = mockk<GoldenDatasetRepository>()
        every { goldenDatasetRepository.findById(datasetId) } returns dataset.let { restoredDatasetWithId(datasetId) }
        val claimedItem = claimed(benchmarkId, benchmarkTarget.targetId, itemId = UUID.randomUUID())
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns listOf(claimedItem)

        worker(benchmarkRepository, goldenDatasetRepository, itemResultRepository).runOnce()

        verify {
            itemResultRepository.markFailed(
                claimedItem.resultId,
                "worker-1",
                match { it.contains("GoldenDatasetItem not found") },
            )
        }
    }

    @OptIn(promptengine.domain.shared.PersistenceApi::class)
    private fun restoredDatasetWithId(id: UUID): GoldenDataset =
        GoldenDataset.restore(
            promptengine.domain.benchmark.GoldenDatasetMemento(
                datasetId = id,
                promptKey = promptKey,
                name = "dataset",
                description = null,
                items = listOf(item()),
            ),
        )

    // ---- finalizeIfComplete ----

    @Test
    fun `全項目完了済みならcompleteを保存する`() {
        val benchmarkTarget = target()
        val (benchmarkRepository, benchmarkId) = runningBenchmarkSetup(benchmarkTarget)
        val dataset = restoredDatasetWithId(datasetId)
        val goldenDatasetRepository = mockk<GoldenDatasetRepository>()
        every { goldenDatasetRepository.findById(datasetId) } returns dataset
        val claimedItem = claimed(benchmarkId, benchmarkTarget.targetId, itemId = dataset.items.single().itemId)
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns listOf(claimedItem)
        every { itemResultRepository.markCompleted(any(), any(), any()) } returns true
        every { itemResultRepository.hasIncomplete(benchmarkId) } returns false

        worker(benchmarkRepository, goldenDatasetRepository, itemResultRepository).runOnce()

        verify {
            benchmarkRepository.save(match { it.benchmarkId == benchmarkId && it.status == BenchmarkStatus.Completed })
        }
    }

    @Test
    fun `未完了項目が残っていればcompleteを保存しない`() {
        val benchmarkTarget = target()
        val (benchmarkRepository, benchmarkId) = runningBenchmarkSetup(benchmarkTarget)
        val dataset = restoredDatasetWithId(datasetId)
        val goldenDatasetRepository = mockk<GoldenDatasetRepository>()
        every { goldenDatasetRepository.findById(datasetId) } returns dataset
        val claimedItem = claimed(benchmarkId, benchmarkTarget.targetId, itemId = dataset.items.single().itemId)
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns listOf(claimedItem)
        every { itemResultRepository.markCompleted(any(), any(), any()) } returns true
        every { itemResultRepository.hasIncomplete(benchmarkId) } returns true

        worker(benchmarkRepository, goldenDatasetRepository, itemResultRepository).runOnce()

        verify(exactly = 0) { benchmarkRepository.save(match { it.status == BenchmarkStatus.Completed }) }
    }

    @Test
    fun `complete保存が失敗してもクラッシュせずWARNログを残す`() {
        val benchmarkTarget = target()
        val benchmark = pendingBenchmark(listOf(benchmarkTarget)).start()
        val benchmarkRepository = mockk<BenchmarkRepository>()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Pending) } returns emptyList()
        every { benchmarkRepository.findByStatus(BenchmarkStatus.Cancelling) } returns emptyList()
        every { benchmarkRepository.findById(benchmark.benchmarkId) } returns benchmark
        every { benchmarkRepository.save(match { it.status == BenchmarkStatus.Completed }) } throws
            IllegalStateException("concurrent update")
        val dataset = restoredDatasetWithId(datasetId)
        val goldenDatasetRepository = mockk<GoldenDatasetRepository>()
        every { goldenDatasetRepository.findById(datasetId) } returns dataset
        val claimedItem =
            claimed(benchmark.benchmarkId, benchmarkTarget.targetId, itemId = dataset.items.single().itemId)
        val itemResultRepository = mockk<BenchmarkItemResultRepository>(relaxed = true)
        every { itemResultRepository.claimBatch(any(), any(), any()) } returns listOf(claimedItem)
        every { itemResultRepository.markCompleted(any(), any(), any()) } returns true
        every { itemResultRepository.hasIncomplete(benchmark.benchmarkId) } returns false

        worker(benchmarkRepository, goldenDatasetRepository, itemResultRepository).runOnce()

        logAppender.list.any { it.formattedMessage.contains("benchmark_completion_finalize_failed") } shouldBe true
    }
}
