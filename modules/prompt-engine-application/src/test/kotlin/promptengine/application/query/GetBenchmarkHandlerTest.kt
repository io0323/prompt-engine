package promptengine.application.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.InMemoryBenchmarkItemResultRepository
import promptengine.application.command.InMemoryBenchmarkRepository
import promptengine.application.command.InMemoryGoldenDatasetRepository
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkNotFoundException
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.benchmark.GoldenDatasetNotFoundException
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.util.UUID

class GetBenchmarkHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private fun handler(
        benchmarkRepository: InMemoryBenchmarkRepository,
        goldenDatasetRepository: InMemoryGoldenDatasetRepository,
        itemResultRepository: InMemoryBenchmarkItemResultRepository,
    ) = GetBenchmarkHandler(benchmarkRepository, goldenDatasetRepository, itemResultRepository)

    private fun dataset(itemCount: Int = 2): GoldenDataset {
        val items = (1..itemCount).map { GoldenDatasetItem(UUID.randomUUID(), emptyMap(), emptyMap(), "expected-$it") }
        return GoldenDataset.create(promptKey, "dataset", null, items)
    }

    @Test
    fun `materialize前 Pending は進捗が全て0`() {
        val ds = dataset()
        val benchmark =
            Benchmark.create(
                promptKey,
                ds.datasetId,
                listOf(BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))),
                setOf(BenchmarkMetricType.Accuracy),
                nRepetitions = 3,
            )
        val benchmarkRepository = InMemoryBenchmarkRepository().apply { seed(benchmark) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository().apply { seed(ds) }
        val itemResultRepository = InMemoryBenchmarkItemResultRepository()

        val view =
            handler(benchmarkRepository, goldenDatasetRepository, itemResultRepository)
                .handle(GetBenchmarkQuery(benchmark.benchmarkId))

        view.status shouldBe "Pending"
        view.estimatedExecutionCount shouldBe 1 * 2 * 3
        view.progress shouldBe
            BenchmarkProgressSummary(totalItems = 0, completedItems = 0, failedItems = 0, pendingItems = 0)
    }

    @Test
    fun `進捗はstatus別の件数を集計する`() {
        val ds = dataset(itemCount = 3)
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(promptKey, ds.datasetId, listOf(target), setOf(BenchmarkMetricType.Accuracy), 3).start()
        val benchmarkRepository = InMemoryBenchmarkRepository().apply { seed(benchmark) }
        val goldenDatasetRepository = InMemoryGoldenDatasetRepository().apply { seed(ds) }
        val itemResultRepository =
            InMemoryBenchmarkItemResultRepository().apply {
                seed(benchmark.benchmarkId, target.targetId, ds.items[0].itemId, "Completed")
                seed(benchmark.benchmarkId, target.targetId, ds.items[1].itemId, "Failed")
                seed(benchmark.benchmarkId, target.targetId, ds.items[2].itemId, "Pending")
            }

        val view =
            handler(benchmarkRepository, goldenDatasetRepository, itemResultRepository)
                .handle(GetBenchmarkQuery(benchmark.benchmarkId))

        view.progress shouldBe
            BenchmarkProgressSummary(totalItems = 3, completedItems = 1, failedItems = 1, pendingItems = 1)
    }

    @Test
    fun `存在しないbenchmarkIdはBenchmarkNotFoundExceptionを投げる`() {
        val handler =
            handler(
                InMemoryBenchmarkRepository(),
                InMemoryGoldenDatasetRepository(),
                InMemoryBenchmarkItemResultRepository(),
            )

        shouldThrow<BenchmarkNotFoundException> { handler.handle(GetBenchmarkQuery(UUID.randomUUID())) }
    }

    @Test
    fun `datasetIdが解決できなければGoldenDatasetNotFoundExceptionを投げる`() {
        val benchmark =
            Benchmark.create(
                promptKey,
                UUID.randomUUID(),
                listOf(BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))),
                setOf(BenchmarkMetricType.Accuracy),
                3,
            )
        val benchmarkRepository = InMemoryBenchmarkRepository().apply { seed(benchmark) }
        val handler =
            handler(benchmarkRepository, InMemoryGoldenDatasetRepository(), InMemoryBenchmarkItemResultRepository())

        shouldThrow<GoldenDatasetNotFoundException> { handler.handle(GetBenchmarkQuery(benchmark.benchmarkId)) }
    }
}
