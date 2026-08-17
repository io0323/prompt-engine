package promptengine.application.pipeline

import promptengine.application.command.sha256
import promptengine.application.experiment.ExperimentVariantResolver
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.shared.IdempotentCommandExecutor
import java.math.BigDecimal

/**
 * `POST /prompts/{key}/execute`（設計書§13.1・§13.2、Full-execution、Stage1〜12）。P8の
 * [PipelineOrchestrator]を呼ぶ薄いUseCase。
 *
 * APAP呼出（Stage 9）を含み数秒〜数十秒かかり得るため、[IdempotentCommandExecutor]の
 * CRUD向け`executeInTransaction`ではなく[IdempotentCommandExecutor.executeLongRunning]を使う
 * （DBトランザクションでコネクションを長時間保持しないため、P9bレビュー指摘）。
 *
 * [ExecuteResult]は設計書§13.2「render+{parsedOutput, rawContent, usage{inputTokens,
 * outputTokens, cost}, latencyMs, evaluationId}」の記述と1:1対応する（P9c）。
 * [ExecuteResult.usage]の`cost`は[promptengine.domain.execution.Usage]自体が保持しない
 * 一次データ（`Usage`のKDoc参照）であるため、`command.request.modelProfile.costPerToken`から
 * 呼出側であるこのUseCaseが導出する。[ExecuteResult.evaluationId]はStage 11
 * （`EvaluationStage`）が同期的にID発行を行わない（イベント発行のみ、設計書§2.6）ため、
 * M1では常に`null`（Evaluation機能の同期API化はM2スコープ）。
 */
data class ExecuteCommand(
    val request: PipelineRequest,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: 他のCommandとfingerprintPayload()の結果が衝突しないため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$request"
}

/** [ExecuteResult.usage]（設計書§13.2 `usage {inputTokens, outputTokens, cost}`）。 */
data class ExecuteUsageSummary(val inputTokens: Int, val outputTokens: Int, val cost: BigDecimal)

/** [ExecuteUseCase.handle]の結果。設計書§13.2のExecuteレスポンス例と1:1対応する。 */
data class ExecuteResult(
    val promptKey: String,
    val version: String?,
    val traceId: String,
    val outputFormat: String?,
    val parsedOutput: Map<String, Any?>,
    val rawContent: String,
    val usage: ExecuteUsageSummary?,
    val latencyMs: Long?,
    val evaluationId: String?,
    val attemptCount: Int,
)

/**
 * [ExecuteCommand]のハンドラ。P8の[PipelineOrchestrator]をFULL_EXECUTIONモードで呼ぶ。
 *
 * [experimentVariantResolver]でPipeline実行前にExperiment割当を解決する（ADR-0034決定2）。
 */
class ExecuteUseCase(
    private val pipelineOrchestrator: PipelineOrchestrator,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
    private val experimentVariantResolver: ExperimentVariantResolver,
) {
    fun handle(command: ExecuteCommand): ExecuteResult =
        idempotentCommandExecutor.executeLongRunning(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            ExecuteResult::class.java,
        ) {
            val resolvedRequest = experimentVariantResolver.resolve(command.request)
            val context = pipelineOrchestrator.run(resolvedRequest, PipelineMode.FULL_EXECUTION, command.traceId)
            val outcome = context.executionOutcome
            val lastAttempt = outcome?.attempts?.lastOrNull()
            val costPerToken = command.request.modelProfile.costPerToken.value
            ExecuteResult(
                promptKey = command.request.promptKey.value,
                version = context.promptVersion?.semVer?.toString(),
                traceId = context.traceId,
                outputFormat = outcome?.parsedOutput?.format?.name,
                parsedOutput = outcome?.parsedOutput?.fields ?: emptyMap(),
                rawContent = lastAttempt?.content?.expose() ?: "",
                usage =
                    lastAttempt?.let {
                        ExecuteUsageSummary(
                            inputTokens = it.usage.inputTokens.value,
                            outputTokens = it.usage.outputTokens.value,
                            cost = BigDecimal(it.usage.inputTokens.value + it.usage.outputTokens.value) * costPerToken,
                        )
                    },
                latencyMs = lastAttempt?.latency?.value,
                evaluationId = null,
                attemptCount = outcome?.attempts?.size ?: 0,
            )
        }
}
