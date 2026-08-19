package promptengine.application.command

import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.BenchmarkTarget
import promptengine.domain.benchmark.GoldenDatasetNotFoundException
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.domain.prompt.LifecycleState
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.PromptRepository
import promptengine.domain.prompt.PromptVersionNotFoundException
import promptengine.domain.prompt.PromptVersionStateNotAllowedException
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.shared.SemVer
import java.util.UUID

/** `POST /benchmarks`（設計書§13.1、ADR-0035）のTarget 1件分の入力。 */
data class BenchmarkTargetInput(val semVer: SemVer)

/** `POST /benchmarks`（設計書§13.1、ADR-0035フェーズ(d)）。 */
data class CreateBenchmarkCommand(
    val promptKey: PromptKey,
    val datasetId: UUID,
    val targets: List<BenchmarkTargetInput>,
    val metrics: Set<BenchmarkMetricType>,
    val nRepetitions: Int,
    val temperature: Double?,
    val actor: String,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    internal fun fingerprintPayload(): String =
        "${this::class.simpleName}:" + listOf(promptKey, datasetId, targets, metrics, nRepetitions, temperature)
}

data class CreateBenchmarkResult(
    val benchmarkId: UUID,
    val promptKey: String,
    val status: String,
    val estimatedExecutionCount: Int,
)

/**
 * Benchmark作成ハンドラ（ADR-0035決定3・決定5）。各Targetが参照する`PromptVersion`の状態が
 * `Approved`/`Published`/`Deprecated`のいずれかであることをここで検証する
 * （[CreateExperimentHandler]と同じ理由・同じ許容状態集合。Draft/InReview参照は拒否）。
 * `estimatedExecutionCount`（ADR-0035決定5「事前コスト見積り」）は`GoldenDataset.items.size`を
 * 読める、このハンドラが唯一の算出箇所。
 */
class CreateBenchmarkHandler(
    private val promptRepository: PromptRepository,
    private val goldenDatasetRepository: GoldenDatasetRepository,
    private val benchmarkRepository: BenchmarkRepository,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
) {
    private val usableStates = setOf(LifecycleState.Approved, LifecycleState.Published, LifecycleState.Deprecated)

    fun handle(command: CreateBenchmarkCommand): CreateBenchmarkResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            CreateBenchmarkResult::class.java,
        ) {
            val prompt =
                promptRepository.findByKey(command.promptKey)
                    ?: throw PromptVersionNotFoundException.forKey(command.promptKey)
            val dataset =
                goldenDatasetRepository.findById(command.datasetId)
                    ?: throw GoldenDatasetNotFoundException(command.datasetId)

            val targets =
                command.targets.map { input ->
                    val version =
                        prompt.versions.find { it.semVer == input.semVer }
                            ?: throw PromptVersionNotFoundException(input.semVer)
                    if (version.state !in usableStates) {
                        throw PromptVersionStateNotAllowedException(version.semVer, version.state)
                    }
                    BenchmarkTarget(UUID.randomUUID(), input.semVer)
                }

            val benchmark =
                Benchmark.create(
                    command.promptKey,
                    command.datasetId,
                    targets,
                    command.metrics,
                    command.nRepetitions,
                    command.temperature,
                )
            val saved = benchmarkRepository.save(benchmark)
            CreateBenchmarkResult(
                benchmarkId = saved.benchmarkId,
                promptKey = saved.promptKey.value,
                status = saved.status::class.simpleName ?: "Unknown",
                estimatedExecutionCount = saved.estimatedExecutionCount(dataset.items.size),
            )
        }
}
