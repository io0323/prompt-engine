package promptengine.application.pipeline

import promptengine.application.command.sha256
import promptengine.domain.pipeline.PipelineMode
import promptengine.domain.pipeline.PipelineRequest
import promptengine.domain.shared.IdempotentCommandExecutor

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
    internal fun fingerprintPayload(): String = request.toString()
}

data class CompileResult(
    val promptKey: String,
    val traceId: String,
    val validationPassed: Boolean,
    val warningCount: Int,
)

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
                warningCount = report?.findings?.size ?: 0,
            )
        }
}
