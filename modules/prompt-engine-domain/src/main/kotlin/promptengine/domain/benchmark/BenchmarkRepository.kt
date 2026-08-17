package promptengine.domain.benchmark

import promptengine.domain.prompt.PromptKey
import java.util.UUID

/**
 * [Benchmark]の永続化インターフェース（ADR-0035）。
 * 実装は`prompt-engine-infrastructure`（`JdbcBenchmarkRepository`）。
 */
interface BenchmarkRepository {
    fun findById(benchmarkId: UUID): Benchmark?

    fun findByPromptKey(promptKey: PromptKey): List<Benchmark>

    fun save(benchmark: Benchmark): Benchmark
}
