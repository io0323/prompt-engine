package promptengine.application.pipeline

import promptengine.domain.execution.ExecutionFailedException
import promptengine.domain.observability.MetricsRecorder
import promptengine.domain.observability.Outcome
import promptengine.domain.observability.TokenDirection
import promptengine.domain.pipeline.InvalidPipelineRequestException
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.pipeline.PipelineTracer
import java.util.UUID

/**
 * Pipeline全体の実行制御（設計書§2.6冒頭、§10アクティビティ図、実装ガイド§6.9）。
 *
 * [PipelineFactory]が選んだ[mode]別のStage列を順に実行し、各Stageの実行を
 * [pipelineTracer]でSpanに包み、`System.nanoTime()`で所要時間を計測して
 * [PipelineContext.stageDurationsMs]へ積み増す（フレームワーク非依存の計測自体は
 * Orchestratorが自前で行い、[PipelineTracer]はSpan生成のみを担う。ADR-0015決定4）。
 * Stageが例外を投げた場合も、その試行にかかった時間を[PipelineContext.stageDurationsMs]へ
 * 積み増してから再送出する（CodeRabbitレビュー指摘: 従来は成功時のみ計測を反映しており、
 * 失敗したStage自身のdurationが`recordFailure`に渡る`AuditRecord`に載らなかった）。
 *
 * 実行前に[validateRequest]で`request`が`mode`の要求を満たすか検証する
 * （例: `FULL_EXECUTION`は`executionPolicy`必須、`PipelineRequest`のKDoc参照）。
 * 満たさない場合は[InvalidPipelineRequestException]を投げる。この検証をStage側の
 * `checkNotNull`（誤配線検出用の防御コード）に任せず`run`の入口で行うのは、
 * 呼出元が修正可能な入力不備を`INTERNAL_ERROR`ではなく`INVALID_REQUEST`
 * （設計書§13.3）として区別して返すため（CodeRabbitレビュー指摘）。
 *
 * いずれかのStageが例外を投げた場合、[StageErrorMapper]でerrorCodeへ変換し、
 * [mode]がFULL_EXECUTIONであれば[auditStage]の`recordFailure`で監査記録した上で、
 * 元の例外をそのまま再送出する（HTTPステータスへの写像は`prompt-engine-interface`の
 * `GlobalExceptionHandler`が別途担う。CLAUDE.mdの例外→HTTP写像の集約方針）。
 * traceIdは全Stage・[promptengine.domain.pipeline.PromptExecutedEvent]・
 * [promptengine.domain.audit.AuditRecord]へ同一値が伝播する（`PipelineContext.traceId`は
 * 生成後、Stageの`copy()`更新でも変更されないため）。
 */
class PipelineOrchestrator(
    private val pipelineFactory: PipelineFactory,
    private val auditStage: AuditStage,
    private val pipelineTracer: PipelineTracer,
    private val metricsRecorder: MetricsRecorder,
) {
    @Suppress("TooGenericExceptionCaught")
    fun run(
        request: PipelineRequest,
        mode: PipelineMode,
        traceId: String = UUID.randomUUID().toString(),
    ): PipelineContext {
        // どのStageがどのdomain例外を投げるかは§13.3の表ごとに異なる（StageErrorMapper参照）ため、
        // 1箇所でAudit記録を行うにはStage横断で共通のException型を捕まえる必要がある。
        var context = PipelineContext(request = request, mode = mode, traceId = traceId)
        try {
            validateRequest(request, mode)
            for (stage in pipelineFactory.stagesFor(mode)) {
                val startedAt = System.nanoTime()
                try {
                    val result = pipelineTracer.withSpan(stage.name, context.traceId) { stage.execute(context) }
                    val durationMs = elapsedMs(startedAt)
                    metricsRecorder.recordStageDuration(stage.name, mode, durationMs)
                    val durations = result.stageDurationsMs + (stage.name to durationMs)
                    context = result.copy(stageDurationsMs = durations)
                } catch (e: Exception) {
                    val durationMs = elapsedMs(startedAt)
                    metricsRecorder.recordStageDuration(stage.name, mode, durationMs)
                    val durations = context.stageDurationsMs + (stage.name to durationMs)
                    context = context.copy(stageDurationsMs = durations)
                    throw e
                }
            }
        } catch (e: ExecutionFailedException) {
            metricsRecorder.incrementExecutionAttempt(Outcome.FAILURE, e.errorType)
            recordFailureAndRethrow(context, mode, e)
        } catch (e: Exception) {
            recordFailureAndRethrow(context, mode, e)
        }
        recordRenderMetrics(context, mode)
        recordExecutionMetrics(context)
        return context
    }

    /**
     * detektの`InstanceOfCheckForException`回避のため、[ExecutionFailedException]専用の
     * `catch`を分離した（`run`参照）。この共通後処理（Render系メトリクス記録・Audit記録・
     * 再送出）は例外の型に関わらず同一のため、ここへ集約する。
     */
    private fun recordFailureAndRethrow(
        context: PipelineContext,
        mode: PipelineMode,
        e: Exception,
    ): Nothing {
        recordRenderMetrics(context, mode)
        if (AuditStage.isAuditable(mode)) {
            auditStage.recordFailure(context, StageErrorMapper.errorCodeFor(e))
        }
        throw e
    }

    /**
     * NFR-003（Render、Validation含む、実行除く、p99≤200ms）の監視に使う`pipeline_render_duration`/
     * `render_count`を記録する。`COMPILE_ONLY`はStage 1〜8の範囲外（Stage 8 Renderingを
     * 含まない）のためスキップする。1件もStageが実行されていない場合（`validateRequest`が
     * Stage実行前に拒否した等）は「Renderを試みてすらいない」ため、これも記録対象外とする
     * （どちらもADR-0027決定1）。
     */
    private fun recordRenderMetrics(
        context: PipelineContext,
        mode: PipelineMode,
    ) {
        if (mode == PipelineMode.COMPILE_ONLY || context.stageDurationsMs.isEmpty()) return
        val outcome = if (context.rendered != null) Outcome.SUCCESS else Outcome.FAILURE
        val renderDurationMs = context.stageDurationsMs.filterKeys { it in RENDER_STAGE_NAMES }.values.sum()
        metricsRecorder.recordRenderDuration(mode, outcome, renderDurationMs)
        metricsRecorder.incrementRenderCount(outcome)
    }

    /**
     * `token_usage_total`/`cost_total`/`execution_attempts_total`（成功側）を記録する。
     * `executionOutcome`はFULL_EXECUTIONのStage 9成功時のみ埋まる（[PipelineContext]のKDoc）ため、
     * これを実行有無の判定に使う。Prompt単位のトークン・コスト分析はこのメトリクスではなく
     * `execution_logs`のクエリで行う契約（ADR-0027決定1）のため、ここではpromptKey等のラベルを
     * 一切使わない。
     */
    private fun recordExecutionMetrics(context: PipelineContext) {
        val outcome = context.executionOutcome ?: return
        val usage = outcome.attempts.last().usage
        metricsRecorder.recordTokenUsage(TokenDirection.INPUT, usage.inputTokens.value.toLong())
        metricsRecorder.recordTokenUsage(TokenDirection.OUTPUT, usage.outputTokens.value.toLong())
        val totalTokens = usage.inputTokens.value.toLong() + usage.outputTokens.value.toLong()
        metricsRecorder.recordCost(context.request.modelProfile.costPerToken.value.multiply(totalTokens.toBigDecimal()))
        metricsRecorder.incrementExecutionAttempt(Outcome.SUCCESS, errorType = null)
    }

    private fun validateRequest(
        request: PipelineRequest,
        mode: PipelineMode,
    ) {
        if (mode == PipelineMode.FULL_EXECUTION && request.executionPolicy == null) {
            throw InvalidPipelineRequestException(
                "PipelineRequest.executionPolicy is required for FULL_EXECUTION mode",
            )
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Stage 1〜8（設計書§2.6、NFR-003の「Render（Validation含む、実行除く）」の範囲）。 */
        private val RENDER_STAGE_NAMES =
            setOf(
                "Load",
                "Merge",
                "Import",
                "ResolveVariables",
                "ResolveContext",
                "Validation",
                "Optimization",
                "Rendering",
            )
    }
}
