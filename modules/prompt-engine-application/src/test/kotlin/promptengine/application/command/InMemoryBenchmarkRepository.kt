package promptengine.application.command

import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.BenchmarkStatus
import promptengine.domain.prompt.PromptKey
import java.util.UUID

/** テスト用フェイク。`row_version`等は検証しない、単純なMapベースの永続化（ADR-0035）。 */
class InMemoryBenchmarkRepository : BenchmarkRepository {
    private val store = mutableMapOf<UUID, Benchmark>()

    fun seed(benchmark: Benchmark) {
        store[benchmark.benchmarkId] = benchmark
    }

    override fun findById(benchmarkId: UUID): Benchmark? = store[benchmarkId]

    override fun findByPromptKey(promptKey: PromptKey): List<Benchmark> =
        store.values.filter { it.promptKey == promptKey }

    override fun findByStatus(status: BenchmarkStatus): List<Benchmark> = store.values.filter { it.status == status }

    override fun save(benchmark: Benchmark): Benchmark {
        store[benchmark.benchmarkId] = benchmark
        return benchmark
    }
}
