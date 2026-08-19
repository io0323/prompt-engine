package promptengine.application.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.application.command.InMemoryBenchmarkItemResultRepository
import promptengine.application.command.InMemoryBenchmarkRepository
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkNotFoundException
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.SemVer
import java.math.BigDecimal
import java.util.UUID

class GetBenchmarkResultsHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private fun handler(
        benchmarkRepository: InMemoryBenchmarkRepository,
        itemResultRepository: InMemoryBenchmarkItemResultRepository,
    ) = GetBenchmarkResultsHandler(benchmarkRepository, itemResultRepository)

    private fun runningBenchmark(target: BenchmarkTarget): Benchmark =
        Benchmark.create(
            promptKey,
            UUID.randomUUID(),
            listOf(target),
            setOf(BenchmarkMetricType.Accuracy),
            nRepetitions = 3,
        ).start()

    @Test
    fun `結果一覧はスコアを含めて返す`() {
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark = runningBenchmark(target)
        val itemId = UUID.randomUUID()
        val benchmarkRepository = InMemoryBenchmarkRepository().apply { seed(benchmark) }
        val itemResultRepository =
            InMemoryBenchmarkItemResultRepository().apply {
                seed(
                    benchmark.benchmarkId,
                    target.targetId,
                    itemId,
                    "Completed",
                    accuracyScore = BigDecimal("0.90"),
                )
            }

        val view =
            handler(benchmarkRepository, itemResultRepository).handle(GetBenchmarkResultsQuery(benchmark.benchmarkId))

        view.benchmarkId shouldBe benchmark.benchmarkId
        view.items shouldBe
            listOf(
                BenchmarkItemResultSummary(
                    targetId = target.targetId,
                    itemId = itemId,
                    status = "Completed",
                    accuracyScore = BigDecimal("0.90"),
                    consistencyScore = null,
                    determinismScore = null,
                    errorMessage = null,
                ),
            )
    }

    @Test
    fun `未着手なら空リストを返す`() {
        val target = BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))
        val benchmark =
            Benchmark.create(promptKey, UUID.randomUUID(), listOf(target), setOf(BenchmarkMetricType.Accuracy), 3)
        val benchmarkRepository = InMemoryBenchmarkRepository().apply { seed(benchmark) }
        val itemResultRepository = InMemoryBenchmarkItemResultRepository()

        val view =
            handler(benchmarkRepository, itemResultRepository).handle(GetBenchmarkResultsQuery(benchmark.benchmarkId))

        view.items shouldBe emptyList()
    }

    @Test
    fun `存在しないbenchmarkIdはBenchmarkNotFoundExceptionを投げる`() {
        val handler = handler(InMemoryBenchmarkRepository(), InMemoryBenchmarkItemResultRepository())

        shouldThrow<BenchmarkNotFoundException> { handler.handle(GetBenchmarkResultsQuery(UUID.randomUUID())) }
    }
}
