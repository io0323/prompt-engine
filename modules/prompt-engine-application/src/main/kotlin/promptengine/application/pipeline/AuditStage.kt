package promptengine.application.pipeline

import promptengine.domain.audit.AuditFailureHandler
import promptengine.domain.audit.AuditOutcome
import promptengine.domain.audit.AuditRecord
import promptengine.domain.audit.AuditRepository
import promptengine.domain.pipeline.PipelineContext
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineStage
import java.time.Instant

/**
 * Stage 12（Audit、設計書§2.6「入力: 全ステージ記録、失敗時はDLQ退避（本流は止めない）」）。
 *
 * [execute]は正常系（ステージ1〜11が全て成功しPipelineが最後まで到達した場合）の記録経路。
 * ステージ1〜11のいずれかが例外を投げてPipelineが中断した場合は、この`execute`自体が
 * 呼ばれないため、`PipelineOrchestrator`が[recordFailure]を直接呼び出して記録する
 * （ADR-0015決定7）。
 *
 * [AuditRepository.append]がどちらの経路でも失敗した場合は例外を伝播させず
 * [auditFailureHandler]へ委譲する（本流を失敗させない契約、設計書§2.6ステージ12）。
 *
 * **Audit自身のdurationについて（CodeRabbitレビュー指摘、設計上の意図的な決定）**:
 * [execute]が[append]で`AuditRecord`を永続化する時点では、Audit自身の所要時間は
 * まだ確定していない（`PipelineOrchestrator`が`execute`の戻り値を受け取った後に
 * 初めて計測が完了する）。そのため、正常系で永続化される`AuditRecord.stageDurationsMs`は
 * ステージ1〜11のdurationのみを含み、"Audit"キー自体は含まない。Audit自身のdurationは
 * `PipelineOrchestrator.run`が返す最終的な[PipelineContext.stageDurationsMs]には
 * 含まれる（呼出元が結果を検査する経路のみ）。`AuditRepository`は追記専用で更新を
 * 提供しないため（`AuditRepository`のKDoc参照）、永続化後に"Audit"キーを追記する
 * 経路は存在しない。この非対称性は構造的な制約であり、欠陥ではない
 * （`AuditStageTest`でこの契約を固定する）。
 */
class AuditStage(
    private val auditRepository: AuditRepository,
    private val auditFailureHandler: AuditFailureHandler,
) : PipelineStage {
    override val name: String = "Audit"

    override fun execute(context: PipelineContext): PipelineContext {
        append(context, AuditOutcome.Success)
        return context
    }

    /** ステージ1〜11のいずれかが[errorCode]で中断した際に`PipelineOrchestrator`が直接呼び出す。 */
    fun recordFailure(
        context: PipelineContext,
        errorCode: String,
    ) {
        append(context, AuditOutcome.Failure(errorCode))
    }

    private fun append(
        context: PipelineContext,
        outcome: AuditOutcome,
    ) {
        val record =
            AuditRecord(
                traceId = context.traceId,
                promptKey = context.request.promptKey.value,
                mode = context.mode,
                stageDurationsMs = context.stageDurationsMs,
                outcome = outcome,
                occurredAt = Instant.now(),
            )
        runCatching { auditRepository.append(record) }
            .onFailure { cause -> runCatching { auditFailureHandler.handle(record, cause) } }
    }

    companion object {
        /** [PipelineMode]がAudit記録の対象かどうか（ADR-0015決定7: FULL_EXECUTIONのみ記録）。 */
        fun isAuditable(mode: PipelineMode): Boolean = mode == PipelineMode.FULL_EXECUTION
    }
}
