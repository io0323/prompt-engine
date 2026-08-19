package promptengine.domain.benchmark

import promptengine.domain.prompt.PromptKey
import java.util.UUID

/**
 * [Benchmark]の永続化層からの復元用Memento（`ExperimentMemento`と同じパターン、ADR-0035）。
 */
data class BenchmarkMemento(
    val benchmarkId: UUID,
    val promptKey: PromptKey,
    val datasetId: UUID,
    val targets: List<BenchmarkTarget>,
    val metrics: Set<BenchmarkMetricType>,
    val nRepetitions: Int,
    val status: BenchmarkStatus,
    val temperature: Double?,
)
