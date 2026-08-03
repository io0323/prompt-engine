package promptengine.application.pipeline

import promptengine.application.command.sha256
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.shared.IdempotentCommandExecutor
import promptengine.domain.validation.Severity

/**
 * `POST /prompts/{key}/compile`（設計書§13.1、Compile-only検証。CI用）。P8の
 * [PipelineOrchestrator]を呼ぶ薄いUseCase（ハンドラにビジネスルールを書かない方針）。
 *
 * DB外部I/Oのみで完結するため[IdempotentCommandExecutor.executeInTransaction]を使う。
 * [PipelineContext][promptengine.domain.pipeline.PipelineContext]自体はJackson往復を
 * 前提としない内部型（一部プロパティがprivateコンストラクタ）を含むため、
 * idempotency記録用の[CompileResult]（単純型のみ）へ要約する。
 */
data class CompileCommand(
    val request: PipelineRequest,
    val traceId: String,
    val idempotencyKey: String? = null,
) {
    // クラス名を先頭に含める: PublishCommand等、フィールド構成が同じ他のCommandと
    // fingerprintPayload()の結果が衝突しないようにするため（レビュー指摘）。
    internal fun fingerprintPayload(): String = "${this::class.simpleName}:$request"
}

/** [CompileUseCase.handle]の結果。§13.2のレスポンス例のうち単純型のみを転記したもの。 */
data class CompileResult(
    val promptKey: String,
    val traceId: String,
    val validationPassed: Boolean,
    val warningCount: Int,
)

/** [CompileCommand]のハンドラ。P8の[PipelineOrchestrator]をCOMPILE_ONLYモードで呼ぶ。 */
class CompileUseCase(
    private val pipelineOrchestrator: PipelineOrchestrator,
    private val idempotentCommandExecutor: IdempotentCommandExecutor,
) {
    fun handle(command: CompileCommand): CompileResult =
        idempotentCommandExecutor.executeInTransaction(
            command.idempotencyKey,
            command.fingerprintPayload().sha256(),
            CompileResult::class.java,
        ) {
            val context = pipelineOrchestrator.run(command.request, PipelineMode.COMPILE_ONLY, command.traceId)
            val report = context.validationReport
            CompileResult(
                promptKey = command.request.promptKey.value,
                traceId = context.traceId,
                validationPassed = report?.hasErrors?.not() ?: true,
                warningCount = report?.findings?.count { it.severity == Severity.WARNING } ?: 0,
            )
        }
}
