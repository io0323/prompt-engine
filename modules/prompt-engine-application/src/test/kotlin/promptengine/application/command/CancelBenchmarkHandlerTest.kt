package promptengine.application.command

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkNotFoundException
import promptengine.domain.benchmark.BenchmarkStatus
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.prompt.PromptKey
import promptengine.domain.shared.InvalidStateTransitionException
import promptengine.domain.shared.SemVer
import java.util.UUID

class CancelBenchmarkHandlerTest {
    private val promptKey = PromptKey("team/greeting")

    private fun handler(repository: InMemoryBenchmarkRepository) =
        CancelBenchmarkHandler(repository, PassthroughIdempotentCommandExecutor())

    private fun runningBenchmark(): Benchmark =
        Benchmark.create(
            promptKey,
            UUID.randomUUID(),
            listOf(BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))),
            setOf(BenchmarkMetricType.Accuracy),
            nRepetitions = 3,
        ).start()

    @Test
    fun `RunningのBenchmarkはCancellingへ遷移する`() {
        val benchmark = runningBenchmark()
        val repository = InMemoryBenchmarkRepository().apply { seed(benchmark) }

        val result = handler(repository).handle(CancelBenchmarkCommand(benchmark.benchmarkId, "user:owner", "trace-1"))

        result.status shouldBe "Cancelling"
        repository.findById(benchmark.benchmarkId)!!.status shouldBe BenchmarkStatus.Cancelling
    }

    @Test
    fun `Pending状態からの呼出はInvalidStateTransitionExceptionを投げる`() {
        val benchmark =
            Benchmark.create(
                promptKey,
                UUID.randomUUID(),
                listOf(BenchmarkTarget(UUID.randomUUID(), SemVer(1, 0, 0))),
                setOf(BenchmarkMetricType.Accuracy),
                nRepetitions = 3,
            )
        val repository = InMemoryBenchmarkRepository().apply { seed(benchmark) }

        shouldThrow<InvalidStateTransitionException> {
            handler(repository).handle(CancelBenchmarkCommand(benchmark.benchmarkId, "user:owner", "trace-1"))
        }
    }

    @Test
    fun `存在しないbenchmarkIdはBenchmarkNotFoundExceptionを投げる`() {
        val repository = InMemoryBenchmarkRepository()

        shouldThrow<BenchmarkNotFoundException> {
            handler(repository).handle(CancelBenchmarkCommand(UUID.randomUUID(), "user:owner", "trace-1"))
        }
    }
}
