package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.benchmark.GoldenDatasetNotFoundException
import promptengine.domain.event.EventContext
import promptengine.domain.prompt.NewPromptVersion
import promptengine.domain.prompt.Prompt
import promptengine.domain.prompt.PromptContent
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import promptengine.domain.shared.SemVer
import java.time.Instant
import java.util.UUID

class CreateBenchmarkHandlerTest {
    private val promptKey = PromptKey("team/greeting")
    private val semVer = SemVer(1, 0, 0)
    private val context =
        EventContext(actor = "user:owner", traceId = "trace-1", occurredAt = Instant.parse("2026-01-01T00:00:00Z"))

    private fun handler(
        promptRepository: InMemoryPromptRepository,
        goldenDatasetRepository: InMemoryGoldenDatasetRepository,
        benchmarkRepository: InMemoryBenchmarkRepository,
    ) = CreateBenchmarkHandler(
        promptRepository,
        goldenDatasetRepository,
        benchmarkRepository,
        PassthroughIdempotentCommandExecutor(),
    )

    private fun approvedPrompt(): Prompt {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val inReview = created.submitForReview(semVer, validationPassed = true)
        return inReview.approve(semVer, approvalCount = 1, requiredApprovalCount = 1)
    }

    private fun dataset(itemCount: Int = 2): GoldenDataset {
        val items =
            (1..itemCount).map {
                GoldenDatasetItem(UUID.randomUUID(), emptyMap(), emptyMap(), "expected-$it")
            }
        return GoldenDataset.create(promptKey, "dataset", null, items)
    }

    private fun command(
        datasetId: UUID,
        targets: List<BenchmarkTargetInput> = listOf(BenchmarkTargetInput(semVer)),
        metrics: Set<BenchmarkMetricType> = setOf(BenchmarkMetricType.Accuracy),
        nRepetitions: Int = 3,
        temperature: Double? = null,
    ) = CreateBenchmarkCommand(
        promptKey = promptKey,
        datasetId = datasetId,
        targets = targets,
        metrics = metrics,
        nRepetitions = nRepetitions,
        temperature = temperature,
        actor = "user:owner",
        traceId = "trace-1",
    )

    @Test
    fun `Approved状態のVersionを参照するTargetでBenchmarkをPendingで作成する`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository().apply { seed(dataset()) }
        val benchmarkRepository = InMemoryBenchmarkRepository()
        val ds = goldenDatasetRepository.findByPromptKey(promptKey).single()

        val result =
            handler(promptRepository, goldenDatasetRepository, benchmarkRepository).handle(command(ds.datasetId))

        result.status shouldBe "Pending"
        result.promptKey shouldBe promptKey.value
        val saved = benchmarkRepository.findById(result.benchmarkId)!!
        saved.targets.map { it.promptVersionSemVer } shouldBe listOf(semVer)
    }

    @Test
    fun `estimatedExecutionCountはtargetCount times datasetSize times nRepetitions`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository().apply { seed(dataset(itemCount = 4)) }
        val benchmarkRepository = InMemoryBenchmarkRepository()
        val ds = goldenDatasetRepository.findByPromptKey(promptKey).single()

        val result =
            handler(promptRepository, goldenDatasetRepository, benchmarkRepository)
                .handle(command(ds.datasetId, nRepetitions = 5))

        result.estimatedExecutionCount shouldBe 1 * 4 * 5
    }

    @Test
    fun `Draft状態のVersionを参照するTargetはPromptVersionStateNotAllowedExceptionで拒否する`() {
        val (created, _) = Prompt.create(promptKey, NewPromptVersion(semVer, PromptContent("body")), context)
        val promptRepository = InMemoryPromptRepository().apply { seed(created) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository().apply { seed(dataset()) }
        val benchmarkRepository = InMemoryBenchmarkRepository()
        val ds = goldenDatasetRepository.findByPromptKey(promptKey).single()

        shouldThrow<PromptVersionStateNotAllowedException> {
            handler(promptRepository, goldenDatasetRepository, benchmarkRepository).handle(command(ds.datasetId))
        }
    }

    @Test
    fun `存在しないPromptKeyはPromptVersionNotFoundExceptionを投げる`() {
        val handler =
            handler(InMemoryPromptRepository(), InMemoryGoldenDatasetRepository(), InMemoryBenchmarkRepository())

        shouldThrow<PromptVersionNotFoundException> { handler.handle(command(UUID.randomUUID())) }
    }

    @Test
    fun `存在しないVersionを参照するTargetはPromptVersionNotFoundExceptionを投げる`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository().apply { seed(dataset()) }
        val benchmarkRepository = InMemoryBenchmarkRepository()
        val ds = goldenDatasetRepository.findByPromptKey(promptKey).single()
        val badCommand = command(ds.datasetId, targets = listOf(BenchmarkTargetInput(SemVer(9, 9, 9))))

        shouldThrow<PromptVersionNotFoundException> {
            handler(promptRepository, goldenDatasetRepository, benchmarkRepository).handle(badCommand)
        }
    }

    @Test
    fun `存在しないdatasetIdはGoldenDatasetNotFoundExceptionを投げる`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository()
        val benchmarkRepository = InMemoryBenchmarkRepository()

        shouldThrow<GoldenDatasetNotFoundException> {
            handler(promptRepository, goldenDatasetRepository, benchmarkRepository).handle(command(UUID.randomUUID()))
        }
    }

    @Test
    fun `Determinism要求時にtemperatureが0_0以外ならIllegalArgumentExceptionを投げる`() {
        val promptRepository = InMemoryPromptRepository().apply { seed(approvedPrompt()) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository().apply { seed(dataset()) }
        val benchmarkRepository = InMemoryBenchmarkRepository()
        val ds = goldenDatasetRepository.findByPromptKey(promptKey).single()
        val badCommand = command(ds.datasetId, metrics = setOf(BenchmarkMetricType.Determinism), temperature = 0.7)

        shouldThrow<IllegalArgumentException> {
            handler(promptRepository, goldenDatasetRepository, benchmarkRepository).handle(badCommand)
        }
    }
}
