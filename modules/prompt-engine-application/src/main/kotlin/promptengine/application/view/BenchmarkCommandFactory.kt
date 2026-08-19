package promptengine.application.view

import promptengine.application.command.BenchmarkTargetInput
import promptengine.application.command.CancelBenchmarkCommand
import promptengine.application.command.CreateBenchmarkCommand
import promptengine.application.command.CreateGoldenDatasetCommand
import promptengine.application.command.GoldenDatasetItemInput
import promptengine.application.query.GetBenchmarkQuery
import promptengine.application.query.GetBenchmarkResultsQuery
import promptengine.application.query.GetGoldenDatasetQuery
import promptengine.domain.benchmark.BenchmarkMetricType
import java.util.UUID

/**
 * `BenchmarkController`/`GoldenDatasetController`が使うCommand/Queryを構築する
 * （[ExperimentCommandFactory]と同じ形、ADR-0035フェーズ(d)）。
 */
object BenchmarkCommandFactory {
    @Suppress("LongParameterList")
    fun createBenchmarkCommand(
        promptKey: String,
        datasetId: String,
        targetSemVers: List<String>,
        metrics: Set<String>,
        nRepetitions: Int,
        temperature: Double?,
        meta: RequestMeta,
    ): CreateBenchmarkCommand =
        CreateBenchmarkCommand(
            promptKey = DomainValueFactory.promptKey(promptKey),
            datasetId = UUID.fromString(datasetId),
            targets = targetSemVers.map { BenchmarkTargetInput(DomainValueFactory.semVer(it)) },
            metrics = metrics.map { BenchmarkMetricType.valueOf(it) }.toSet(),
            nRepetitions = nRepetitions,
            temperature = temperature,
            actor = meta.actor,
            traceId = meta.traceId,
            idempotencyKey = meta.idempotencyKey,
        )

    fun getBenchmarkQuery(benchmarkId: UUID): GetBenchmarkQuery = GetBenchmarkQuery(benchmarkId)

    fun getBenchmarkResultsQuery(benchmarkId: UUID): GetBenchmarkResultsQuery = GetBenchmarkResultsQuery(benchmarkId)

    fun cancelBenchmarkCommand(
        benchmarkId: UUID,
        meta: RequestMeta,
    ): CancelBenchmarkCommand = CancelBenchmarkCommand(benchmarkId, meta.actor, meta.traceId, meta.idempotencyKey)

    fun createGoldenDatasetCommand(
        promptKey: String,
        name: String,
        description: String?,
        items: List<GoldenDatasetItemInput>,
        meta: RequestMeta,
    ): CreateGoldenDatasetCommand =
        CreateGoldenDatasetCommand(
            promptKey = DomainValueFactory.promptKey(promptKey),
            name = name,
            description = description,
            items = items,
            actor = meta.actor,
            traceId = meta.traceId,
            idempotencyKey = meta.idempotencyKey,
        )

    fun getGoldenDatasetQuery(datasetId: UUID): GetGoldenDatasetQuery = GetGoldenDatasetQuery(datasetId)
}
