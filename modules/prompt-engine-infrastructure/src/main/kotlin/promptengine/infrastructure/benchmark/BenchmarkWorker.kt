package promptengine.infrastructure.benchmark

import org.slf4j.LoggerFactory
import promptengine.domain.benchmark.Benchmark
import promptengine.domain.benchmark.BenchmarkItemKey
import promptengine.domain.benchmark.BenchmarkItemResultRepository
import promptengine.domain.benchmark.BenchmarkItemScores
import promptengine.domain.benchmark.BenchmarkMetricType
import promptengine.domain.benchmark.BenchmarkRepository
import promptengine.domain.benchmark.BenchmarkScoringRule
import promptengine.domain.benchmark.BenchmarkStatus
import promptengine.domain.benchmark.ClaimedBenchmarkItem
import promptengine.domain.benchmark.GoldenDataset
import promptengine.domain.benchmark.GoldenDatasetItem
import promptengine.domain.benchmark.GoldenDatasetRepository
import promptengine.domain.execution.ExecutionPolicy
import promptengine.domain.optimization.ModelProfile
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.pipeline.PipelineRunner
import promptengine.domain.prompt.PromptKey
import promptengine.domain.prompt.VersionRef
import promptengine.domain.render.ModelHints
import promptengine.domain.shared.PromptRequest
import promptengine.domain.shared.SemVer
import promptengine.domain.shared.TokenCount
import java.time.Duration
import java.util.UUID

/**
 * Benchmark実行ワーカー（ADR-0035決定3、フェーズ(c)）。`OutboxRelayer`と同じ3フェーズ
 * （Claim→実行（トランザクション外）→フェンシング付き確定）で`benchmark_item_results`を処理する。
 *
 * `prompt-engine-application`の[promptengine.application.pipeline.PipelineOrchestrator]を
 * 直接呼べない（`prompt-engine-infrastructure`は`prompt-engine-domain`のみに依存する、
 * CLAUDE.md）ため、依存を逆転させた[PipelineRunner]（domain Interface）経由で呼ぶ。
 *
 * 呼出元（`prompt-engine-bootstrap`の`@Scheduled`ジョブ）が[runOnce]をポーリング間隔ごとに
 * 呼ぶ想定（`OutboxRelayScheduler`と同じ配線）。1回の[runOnce]は次の順で処理する
 * （ADR-0035決定3）:
 * 1. [pickUpPendingBenchmarks] — `Pending`のBenchmarkを`Running`へ遷移させ、項目を用意する。
 * 2. [finalizeCancellingBenchmarks] — `Cancelling`のBenchmarkを`Cancelled`へ遷移させる
 *    （新規Claim前に行う。実行中の項目を強制中断はしない）。
 * 3. [BenchmarkItemResultRepository.claimBatch]で項目をClaimし、1件ずつ処理する。
 * 4. 処理した項目が属するBenchmarkのうち、全項目が終端状態になったものを`Completed`へ遷移させる。
 */
@Suppress("LongParameterList")
class BenchmarkWorker(
    private val benchmarkRepository: BenchmarkRepository,
    private val goldenDatasetRepository: GoldenDatasetRepository,
    private val itemResultRepository: BenchmarkItemResultRepository,
    private val pipelineRunner: PipelineRunner,
    private val scoringRules: List<BenchmarkScoringRule>,
    private val modelProfile: ModelProfile,
    private val instanceId: String,
    private val claimTimeout: Duration = DEFAULT_CLAIM_TIMEOUT,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val executionTimeoutMs: Long = DEFAULT_EXECUTION_TIMEOUT_MS,
) {
    /** 1バッチ分の処理を実行し、Claimして処理した項目数を返す。 */
    fun runOnce(): Int {
        pickUpPendingBenchmarks()
        finalizeCancellingBenchmarks()

        val claimed = itemResultRepository.claimBatch(instanceId, claimTimeout, batchSize)
        val benchmarkCache = mutableMapOf<UUID, Benchmark>()
        val datasetCache = mutableMapOf<UUID, GoldenDataset>()
        claimed.forEach { item -> processItem(item, benchmarkCache, datasetCache) }
        claimed.map { it.benchmarkId }.distinct().forEach { finalizeIfComplete(it) }
        return claimed.size
    }

    /**
     * `Pending`→`Running`。複数ワーカーが同じBenchmarkを同時に拾っても、[materialize]は
     * 冪等（`ON CONFLICT DO NOTHING`）で`save`もCAS不要（ADR-0035決定3、項目単位Claimが
     * 実際の多重実行防止を担うため、この段階の競合は無害）。
     *
     * `start()`（`save`）を[materialize]より**先**に行う: `JdbcBenchmarkRepository.save`は
     * `benchmark_targets`を呼ぶたびに無条件でDELETE→再INSERTする（`replaceTargets`、
     * `targets`自体が変わっていなくても実行される）。[materialize]を先に行うと、
     * `benchmark_item_results.target_id`のFKが`benchmark_targets`の行を参照した状態で
     * 後続の`save(start())`がそのDELETEを試みることになり、外部キー制約違反で失敗する。
     * 先に`start()`を確定させ`targets`を安定させてから[materialize]する。
     *
     * `start()`自体が失敗した場合（他ワーカーとの競合等）は`benchmark`がメモリ上まだ
     * `Pending`のままのため`fail()`を呼べず、単にスキップして次のポーリングに委ねる
     * （安全側。二重に`start()`が試みられるだけで実害は無い）。[materialize]が失敗した場合は
     * `started`（`status=Running`）を使って`fail()`を呼び、`Running`のまま
     * `benchmark_item_results`が0件という中途半端な状態を残さない。
     */
    private fun pickUpPendingBenchmarks() {
        benchmarkRepository.findByStatus(BenchmarkStatus.Pending).forEach { benchmark ->
            val started =
                runCatching { benchmarkRepository.save(benchmark.start()) }
                    .getOrNull() ?: return@forEach

            runCatching {
                val datasetId = started.datasetId
                val dataset =
                    goldenDatasetRepository.findById(datasetId)
                        ?: error("GoldenDataset not found: $datasetId")
                val pairs =
                    started.targets.flatMap { target ->
                        dataset.items.map { item -> BenchmarkItemKey(target.targetId, item.itemId) }
                    }
                itemResultRepository.materialize(pairs)
            }.onFailure { e ->
                logger.warn("benchmark_pickup_failed benchmarkId={}", started.benchmarkId, e)
                runCatching { benchmarkRepository.save(started.fail()) }
            }
        }
    }

    /** `Cancelling`→`Cancelled`。実行中の項目を強制中断せず、次のポーリングを待つ（ADR-0035決定3）。 */
    private fun finalizeCancellingBenchmarks() {
        benchmarkRepository.findByStatus(BenchmarkStatus.Cancelling).forEach { benchmark ->
            runCatching { benchmarkRepository.save(benchmark.cancel()) }
                .onFailure { e ->
                    logger.warn("benchmark_cancel_finalize_failed benchmarkId={}", benchmark.benchmarkId, e)
                }
        }
    }

    private fun processItem(
        claimed: ClaimedBenchmarkItem,
        benchmarkCache: MutableMap<UUID, Benchmark>,
        datasetCache: MutableMap<UUID, GoldenDataset>,
    ) {
        runCatching {
            val benchmark =
                benchmarkCache.getOrPut(claimed.benchmarkId) {
                    benchmarkRepository.findById(claimed.benchmarkId)
                        ?: error("Benchmark not found: ${claimed.benchmarkId}")
                }
            val target =
                benchmark.targets.find { it.targetId == claimed.targetId }
                    ?: error("BenchmarkTarget not found: ${claimed.targetId}")
            val dataset =
                datasetCache.getOrPut(benchmark.datasetId) {
                    goldenDatasetRepository.findById(benchmark.datasetId)
                        ?: error("GoldenDataset not found: ${benchmark.datasetId}")
                }
            val item =
                dataset.items.find { it.itemId == claimed.itemId }
                    ?: error("GoldenDatasetItem not found: ${claimed.itemId}")

            val effectiveTemperature =
                if (BenchmarkMetricType.Determinism in benchmark.metrics) {
                    DETERMINISTIC_TEMPERATURE
                } else {
                    benchmark.temperature
                }
            val outputs =
                (1..benchmark.nRepetitions).map {
                    executeOnce(benchmark.promptKey, target.promptVersionSemVer, item, effectiveTemperature)
                }
            val scores = scoreOutputs(benchmark.metrics, outputs, item.expectedOutput)
            itemResultRepository.markCompleted(claimed.resultId, instanceId, scores)
        }.onFailure { e ->
            val errorMessage = e.message ?: (e::class.simpleName ?: "unknown error")
            itemResultRepository.markFailed(claimed.resultId, instanceId, errorMessage)
        }
    }

    private fun scoreOutputs(
        metrics: Set<BenchmarkMetricType>,
        outputs: List<String>,
        expectedOutput: String?,
    ): BenchmarkItemScores {
        fun scoreFor(metricType: String) =
            scoringRules.find { it.metricType == metricType }?.score(outputs, expectedOutput)
        return BenchmarkItemScores(
            accuracyScore = if (BenchmarkMetricType.Accuracy in metrics) scoreFor("Accuracy") else null,
            consistencyScore = if (BenchmarkMetricType.Consistency in metrics) scoreFor("Consistency") else null,
            determinismScore = if (BenchmarkMetricType.Determinism in metrics) scoreFor("Determinism") else null,
        )
    }

    private fun executeOnce(
        promptKey: PromptKey,
        semVer: SemVer,
        item: GoldenDatasetItem,
        temperature: Double?,
    ): String {
        val request =
            PipelineRequest(
                promptKey = promptKey,
                versionRef = VersionRef.Fixed(semVer),
                variableResolution =
                    PromptRequest(
                        explicitParameters = item.parameters.filterNonNull(),
                        contextData = item.context.mapValues { it.value.filterNonNull() },
                    ),
                modelProfile = modelProfile,
                budget = TokenCount(modelProfile.maxContextTokens.value),
                executionPolicy = ExecutionPolicy(timeoutMs = executionTimeoutMs),
                modelHints = temperature?.let { ModelHints(it) },
            )
        val context = pipelineRunner.run(request, PipelineMode.FULL_EXECUTION, UUID.randomUUID().toString())
        val outcome = checkNotNull(context.executionOutcome) { "pipeline run produced no executionOutcome" }
        return outcome.attempts.last().content.expose()
    }

    private fun finalizeIfComplete(benchmarkId: UUID) {
        if (itemResultRepository.hasIncomplete(benchmarkId)) return
        val benchmark = benchmarkRepository.findById(benchmarkId)
        if (benchmark == null || benchmark.status != BenchmarkStatus.Running) return
        runCatching { benchmarkRepository.save(benchmark.complete()) }
            .onFailure { e -> logger.warn("benchmark_completion_finalize_failed benchmarkId={}", benchmarkId, e) }
    }

    private fun Map<String, Any?>.filterNonNull(): Map<String, Any> =
        mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    companion object {
        private val logger = LoggerFactory.getLogger(BenchmarkWorker::class.java)
        private const val DETERMINISTIC_TEMPERATURE = 0.0
        private val DEFAULT_CLAIM_TIMEOUT = Duration.ofSeconds(30)
        private const val DEFAULT_BATCH_SIZE = 20
        private const val DEFAULT_EXECUTION_TIMEOUT_MS = 30_000L
    }
}
