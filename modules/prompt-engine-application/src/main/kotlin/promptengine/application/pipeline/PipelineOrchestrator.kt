package promptengine.application.pipeline

import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.pipeline.PipelineStage
import promptengine.domain.pipeline.PipelineTracer
import java.util.UUID

/**
 * Pipeline全体の実行制御（設計書§2.6冒頭、§10アクティビティ図、実装ガイド§6.9）。
 *
 * [PipelineFactory]が選んだ[mode]別のStage列を順に実行し、各Stageの実行を
 * [pipelineTracer]でSpanに包み、`System.nanoTime()`で所要時間を計測して
 * [PipelineContext.stageDurationsMs]へ積み増す（フレームワーク非依存の計測自体は
 * Orchestratorが自前で行い、[PipelineTracer]はSpan生成のみを担う。ADR-0015決定4）。
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
        for (stage in pipelineFactory.stagesFor(mode)) {
            context =
                try {
                    runStage(stage, context)
                } catch (e: Exception) {
                    if (AuditStage.isAuditable(mode)) {
                        val errorCode = StageErrorMapper.errorCodeFor(e)
                        auditStage.recordFailure(context, errorCode)
                    }
                    throw e
                }
        }
        return context
    }

    private fun runStage(
        stage: PipelineStage,
        context: PipelineContext,
    ): PipelineContext {
        val startedAt = System.nanoTime()
        val result = pipelineTracer.withSpan(stage.name, context.traceId) { stage.execute(context) }
        val durationMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        return result.copy(stageDurationsMs = result.stageDurationsMs + (stage.name to durationMs))
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
