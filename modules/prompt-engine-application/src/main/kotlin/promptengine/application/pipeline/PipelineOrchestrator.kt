package promptengine.application.pipeline

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
                    val durations = result.stageDurationsMs + (stage.name to elapsedMs(startedAt))
                    context = result.copy(stageDurationsMs = durations)
                } catch (e: Exception) {
                    val durations = context.stageDurationsMs + (stage.name to elapsedMs(startedAt))
                    context = context.copy(stageDurationsMs = durations)
                    throw e
                }
            }
        } catch (e: Exception) {
            if (AuditStage.isAuditable(mode)) {
                auditStage.recordFailure(context, StageErrorMapper.errorCodeFor(e))
            }
            throw e
        }
        return context
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
    }
}
